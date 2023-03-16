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

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants;
import org.openhab.binding.energidataservice.internal.PriceListParser;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecords;
import org.openhab.binding.energidataservice.internal.api.serialization.InstantDeserializer;
import org.openhab.binding.energidataservice.internal.api.serialization.LocalDateTimeDeserializer;
import org.openhab.binding.energidataservice.internal.handler.EnergiDataServiceHandler;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * Tests for {@link EnergiDataServiceActions}.
 * 
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class EnergiDataServiceActionsTest {

    private @NonNullByDefault({}) @Mock EnergiDataServiceHandler handler;
    private EnergiDataServiceActions actions = new EnergiDataServiceActions();

    private Gson gson = new GsonBuilder().registerTypeAdapter(Instant.class, new InstantDeserializer())
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeDeserializer()).create();

    private record SpotPrice(Instant hourStart, BigDecimal spotPrice) {
    }

    private <T> T getObjectFromJson(String filename, Class<T> clazz) throws IOException {
        try (InputStream inputStream = EnergiDataServiceActionsTest.class.getResourceAsStream(filename)) {
            if (inputStream == null) {
                throw new IOException("Input stream is null");
            }
            byte[] bytes = inputStream.readAllBytes();
            if (bytes == null) {
                throw new IOException("Resulting byte-array empty");
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            return Objects.requireNonNull(gson.fromJson(json, clazz));
        }
    }

    @BeforeEach
    void setUp() {
        final Logger logger = (Logger) LoggerFactory.getLogger(EnergiDataServiceActions.class);
        logger.setLevel(Level.OFF);

        actions = new EnergiDataServiceActions();
    }

    @Test
    void getPricesSpotPrice() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("SpotPrice");
        assertThat(actual.size(), is(35));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("0.992840027"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("1.267680054"))));
    }

    @Test
    void getPricesNetTariff() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("NetTariff");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("0.432225"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("1.05619"))));
    }

    @Test
    void getPricesSystemTariff() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("SystemTariff");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("0.054"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("0.054"))));
    }

    @Test
    void getPricesElectricityTax() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("ElectricityTax");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("0.008"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("0.008"))));
    }

    @Test
    void getPricesTransmissionNetTariff() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("TransmissionNetTariff");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("0.058"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("0.058"))));
    }

    @Test
    void getPricesSpotPriceNetTariff() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("SpotPrice,NetTariff");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("1.425065027"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("2.323870054"))));
    }

    @Test
    void getPricesSpotPriceNetTariffElectricityTax() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("SpotPrice,NetTariff,ElectricityTax");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("1.433065027"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("2.331870054"))));
    }

    @Test
    void getPricesTotal() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices();
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("1.545065027"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("2.443870054"))));
    }

    @Test
    void getPricesTotalAllElements() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions
                .getPrices("spotprice,nettariff,systemtariff,electricitytax,transmissionnettariff");
        assertThat(actual.size(), is(36));
        assertThat(actual.get(Instant.parse("2023-02-04T12:00:00Z")), is(equalTo(new BigDecimal("1.545065027"))));
        assertThat(actual.get(Instant.parse("2023-02-04T15:00:00Z")), is(equalTo(new BigDecimal("1.708765039"))));
        assertThat(actual.get(Instant.parse("2023-02-04T16:00:00Z")), is(equalTo(new BigDecimal("2.443870054"))));
    }

    @Test
    void getPricesInvalidPriceElement() throws IOException {
        mockCommonDatasets(actions);

        Map<Instant, BigDecimal> actual = actions.getPrices("spotprice,nettarif");
        assertThat(actual.size(), is(0));
    }

    @Test
    void getPricesMixedCurrencies() throws IOException {
        mockCommonDatasets(actions);
        when(handler.getCurrency()).thenReturn(EnergiDataServiceBindingConstants.CURRENCY_EUR);

        Map<Instant, BigDecimal> actual = actions.getPrices("spotprice,nettariff");
        assertThat(actual.size(), is(0));
    }

    /**
     * Calculate price in period 15:30-16:30 (UTC) with consumption 150 W and the following total prices:
     * 15:00:00: 1.708765039
     * 16:00:00: 2.443870054
     *
     * Result = (1.708765039 / 2) + (2.443870054 / 2) * 0.150
     *
     * @throws IOException
     */
    @Test
    void calculatePriceSimple() throws IOException {
        mockCommonDatasets(actions);

        BigDecimal actual = actions.calculatePrice(Instant.parse("2023-02-04T15:30:00Z"),
                Instant.parse("2023-02-04T16:30:00Z"), new QuantityType<>(150, Units.WATT));
        assertThat(actual, is(equalTo(new BigDecimal("0.311447631975000000")))); // 0.3114476319750
    }

    /**
     * Calculate price in period 15:00-17:00 (UTC) with consumption 1000 W and the following total prices:
     * 15:00:00: 1.708765039
     * 16:00:00: 2.443870054
     *
     * Result = 1.708765039 + 2.443870054
     *
     * @throws IOException
     */
    @Test
    void calculatePriceFullHours() throws IOException {
        mockCommonDatasets(actions);

        BigDecimal actual = actions.calculatePrice(Instant.parse("2023-02-04T15:00:00Z"),
                Instant.parse("2023-02-04T17:00:00Z"), new QuantityType<>(1, Units.KILOVAR));
        assertThat(actual, is(equalTo(new BigDecimal("4.152635093000000000")))); // 4.152635093
    }

    @Test
    void calculatePriceOutOfRange() throws IOException {
        mockCommonDatasets(actions);

        BigDecimal actual = actions.calculatePrice(Instant.parse("2023-02-04T11:30:00Z"),
                Instant.parse("2023-02-04T12:30:00Z"), new QuantityType<>(150, Units.WATT));
        assertThat(actual, is(equalTo(BigDecimal.ZERO)));
    }

    private void mockCommonDatasets(EnergiDataServiceActions actions) throws IOException {
        SpotPrice[] spotPriceRecords = getObjectFromJson("SpotPrices.json", SpotPrice[].class);
        Map<Instant, BigDecimal> spotPrices = Arrays.stream(spotPriceRecords)
                .collect(Collectors.toMap(SpotPrice::hourStart, SpotPrice::spotPrice));

        PriceListParser priceListParser = new PriceListParser(
                Clock.fixed(spotPriceRecords[0].hourStart, EnergiDataServiceBindingConstants.DATAHUB_TIMEZONE));
        DatahubPricelistRecords datahubRecords = getObjectFromJson("NetTariffs.json", DatahubPricelistRecords.class);
        Map<Instant, BigDecimal> netTariffs = priceListParser
                .toHourly(Arrays.stream(datahubRecords.records()).toList());
        datahubRecords = getObjectFromJson("SystemTariffs.json", DatahubPricelistRecords.class);
        Map<Instant, BigDecimal> systemTariffs = priceListParser
                .toHourly(Arrays.stream(datahubRecords.records()).toList());
        datahubRecords = getObjectFromJson("ElectricityTaxes.json", DatahubPricelistRecords.class);
        Map<Instant, BigDecimal> electricityTaxes = priceListParser
                .toHourly(Arrays.stream(datahubRecords.records()).toList());
        datahubRecords = getObjectFromJson("TransmissionNetTariffs.json", DatahubPricelistRecords.class);
        Map<Instant, BigDecimal> transmissionNetTariffs = priceListParser
                .toHourly(Arrays.stream(datahubRecords.records()).toList());

        when(handler.getSpotPrices()).thenReturn(spotPrices);
        when(handler.getNetTariffs()).thenReturn(netTariffs);
        when(handler.getSystemTariffs()).thenReturn(systemTariffs);
        when(handler.getElectricityTaxes()).thenReturn(electricityTaxes);
        when(handler.getTransmissionNetTariffs()).thenReturn(transmissionNetTariffs);
        when(handler.getCurrency()).thenReturn(EnergiDataServiceBindingConstants.CURRENCY_DKK);
        actions.setThingHandler(handler);
    }
}
