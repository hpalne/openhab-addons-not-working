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
package org.openhab.binding.energidataservice.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import javax.measure.quantity.Power;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides calculations based on price maps.
 * This is the current stage of evolution.
 * Ideally this binding would simply provide data in a well-defined format for
 * openHAB core. Operations on this data could then be implemented in core.
 * This way there would be a unified interface from rules, and the calculations
 * could be reused between different data providers (bindings).
 * 
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class PriceCalculator {

    private final Logger logger = LoggerFactory.getLogger(PriceCalculator.class);

    private final Map<Instant, BigDecimal> priceMap;

    public PriceCalculator(Map<Instant, BigDecimal> priceMap) {
        this.priceMap = priceMap;
    }

    public BigDecimal calculatePrice(Instant start, Instant end, QuantityType<Power> power) {
        QuantityType<Power> quantityInWatt = power.toUnit(Units.WATT);
        if (quantityInWatt == null) {
            logger.warn("Invalid unit '{}', expected Power unit", power.getUnit());
            return BigDecimal.ZERO;
        }
        BigDecimal watt = new BigDecimal(quantityInWatt.intValue());

        Instant current = start;
        BigDecimal result = BigDecimal.ZERO;
        while (current.isBefore(end)) {
            Instant hourStart = current.truncatedTo(ChronoUnit.HOURS);
            Instant hourEnd = hourStart.plus(1, ChronoUnit.HOURS);

            BigDecimal currentPrice = priceMap.get(hourStart);
            if (currentPrice == null) {
                logger.warn("Price missing at {}", hourStart);
                return BigDecimal.ZERO;
            }

            Instant currentStart = hourStart;
            if (start.isAfter(hourStart)) {
                currentStart = start;
            }
            Instant currentEnd = hourEnd;
            if (end.isBefore(hourEnd)) {
                currentEnd = end;
            }

            // E(kWh) = P(W) × t(hr) / 1000
            Duration duration = Duration.between(currentStart, currentEnd);
            BigDecimal contribution = currentPrice.multiply(watt).multiply(
                    new BigDecimal(duration.getSeconds()).divide(new BigDecimal(3600000), 9, RoundingMode.HALF_UP));
            result = result.add(contribution);

            current = hourEnd;
        }

        return result;
    }
}
