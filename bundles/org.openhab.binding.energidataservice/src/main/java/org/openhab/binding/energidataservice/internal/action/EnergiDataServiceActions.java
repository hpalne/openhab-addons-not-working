/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.energidataservice.internal.action;

import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.energidataservice.internal.PriceCalculator;
import org.openhab.binding.energidataservice.internal.handler.EnergiDataServiceHandler;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.ActionOutput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EnergiDataServiceActions} provides actions for getting energy data into a rule context.
 *
 * @author Jacob Laursen - Initial contribution
 */
@ThingActionsScope(name = "energidataservice")
@NonNullByDefault
public class EnergiDataServiceActions implements ThingActions {

    private final Logger logger = LoggerFactory.getLogger(EnergiDataServiceActions.class);

    private @Nullable EnergiDataServiceHandler handler;

    private enum PriceElement {
        SPOT_PRICE("spotprice"),
        NET_TARIFF("nettariff"),
        SYSTEM_TARIFF("systemtariff"),
        ELECTRICITY_TAX("electricitytax"),
        TRANSMISSION_NET_TARIFF("transmissionnettariff");

        private static final Map<String, PriceElement> NAME_MAP = Stream.of(values())
                .collect(Collectors.toMap(PriceElement::toString, Function.identity()));

        private String name;

        private PriceElement(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        public static PriceElement fromString(final String name) {
            PriceElement myEnum = NAME_MAP.get(name.toLowerCase());
            if (null == myEnum) {
                throw new IllegalArgumentException(String.format("'%s' has no corresponding value. Accepted values: %s",
                        name, Arrays.asList(values())));
            }
            return myEnum;
        }
    }

    @RuleAction(label = "@text/action.get-prices.label", description = "@text/action.get-prices.description")
    public @ActionOutput(name = "prices", type = "java.util.Map<java.time.Instant, java.math.BigDecimal>") Map<Instant, BigDecimal> getPrices() {
        return getPrices(Arrays.stream(PriceElement.values()).collect(Collectors.toSet()));
    }

    @RuleAction(label = "@text/action.get-prices.label", description = "@text/action.get-prices.description")
    public @ActionOutput(name = "prices", type = "java.util.Map<java.time.Instant, java.math.BigDecimal>") Map<Instant, BigDecimal> getPrices(
            @ActionInput(name = "priceElements", label = "@text/action.get-prices.priceElements.label", description = "@text/action.get-prices.priceElements.description") @Nullable String priceElements) {
        if (priceElements == null) {
            logger.warn("Argument 'priceElements' is null");
            return Map.of();
        }

        Set<PriceElement> priceElementsSet;
        try {
            priceElementsSet = new HashSet<PriceElement>(
                    Arrays.stream(priceElements.split(",")).map(PriceElement::fromString).toList());
        } catch (IllegalArgumentException e) {
            logger.warn("{}", e.getMessage());
            return Map.of();
        }

        return getPrices(priceElementsSet);
    }

    @RuleAction(label = "@text/action.calculate-price.label", description = "@text/action.calculate-price.description")
    public @ActionOutput(name = "price", type = "java.math.BigDecimal") BigDecimal calculatePrice(
            @ActionInput(name = "start", type = "java.time.Instant") Instant start,
            @ActionInput(name = "end", type = "java.time.Instant") Instant end,
            @ActionInput(name = "power", type = "QuantityType<Power>") QuantityType<Power> power) {
        PriceCalculator priceCalculator = new PriceCalculator(getPrices());

        return priceCalculator.calculatePrice(start, end, power);
    }

    private Map<Instant, BigDecimal> getPrices(Set<PriceElement> priceElements) {
        EnergiDataServiceHandler handler = this.handler;
        if (handler == null) {
            logger.warn("EnergiDataServiceActions ThingHandler is null.");
            return Map.of();
        }

        Map<Instant, BigDecimal> prices;
        if (priceElements.contains(PriceElement.SPOT_PRICE)) {
            if (priceElements.size() > 1 && !handler.getCurrency().equals(CURRENCY_DKK)) {
                logger.warn("Cannot calculate sum when spot price currency is {}", handler.getCurrency());
                return Map.of();
            }
            prices = handler.getSpotPrices();
        } else {
            prices = new HashMap<>();
        }

        if (priceElements.contains(PriceElement.NET_TARIFF)) {
            Map<Instant, BigDecimal> netTariffMap = handler.getNetTariffs();
            mergeMaps(prices, netTariffMap);
        }

        if (priceElements.contains(PriceElement.SYSTEM_TARIFF)) {
            Map<Instant, BigDecimal> systemTariffMap = handler.getSystemTariffs();
            mergeMaps(prices, systemTariffMap);
        }

        if (priceElements.contains(PriceElement.ELECTRICITY_TAX)) {
            Map<Instant, BigDecimal> electricityTaxMap = handler.getElectricityTaxes();
            mergeMaps(prices, electricityTaxMap);
        }

        if (priceElements.contains(PriceElement.TRANSMISSION_NET_TARIFF)) {
            Map<Instant, BigDecimal> transmissionNetTariffMap = handler.getTransmissionNetTariffs();
            mergeMaps(prices, transmissionNetTariffMap);
        }

        return prices;
    }

    private void mergeMaps(Map<Instant, BigDecimal> destinationMap, Map<Instant, BigDecimal> sourceMap) {
        for (Entry<Instant, BigDecimal> source : sourceMap.entrySet()) {
            Instant key = source.getKey();
            BigDecimal sourceValue = source.getValue();
            BigDecimal destinationValue = destinationMap.get(key);
            if (destinationValue == null) {
                destinationMap.put(key, sourceValue);
            } else {
                destinationMap.put(key, sourceValue.add(destinationValue));
            }
        }
    }

    /**
     * Static get prices method for DSL rule compatibility.
     *
     * @param actions
     * @param priceElements Comma-separated list of price elements to include in prices.
     * @return Map of prices
     */
    public static Map<Instant, BigDecimal> getPrices(@Nullable ThingActions actions, @Nullable String priceElements) {
        if (actions instanceof EnergiDataServiceActions) {
            if (priceElements != null && !priceElements.isBlank()) {
                return ((EnergiDataServiceActions) actions).getPrices(priceElements);
            } else {
                return ((EnergiDataServiceActions) actions).getPrices();
            }
        } else {
            throw new IllegalArgumentException("Instance is not an EnergiDataServiceActions class.");
        }
    }

    /**
     * Static get prices method for DSL rule compatibility.
     *
     * @param actions
     * @param start Start time
     * @param end End time
     * @param power Constant power consumption
     * @return Map of prices
     */
    public static BigDecimal calculatePrice(@Nullable ThingActions actions, @Nullable Instant start,
            @Nullable Instant end, @Nullable QuantityType<Power> power) {
        if (start == null || end == null || power == null) {
            return BigDecimal.ZERO;
        }
        if (actions instanceof EnergiDataServiceActions) {
            return ((EnergiDataServiceActions) actions).calculatePrice(start, end, power);
        } else {
            throw new IllegalArgumentException("Instance is not an EnergiDataServiceActions class.");
        }
    }

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        if (handler instanceof EnergiDataServiceHandler) {
            this.handler = (EnergiDataServiceHandler) handler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }
}
