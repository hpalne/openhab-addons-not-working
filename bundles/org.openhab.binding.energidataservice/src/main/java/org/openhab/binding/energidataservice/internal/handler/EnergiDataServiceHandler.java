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
package org.openhab.binding.energidataservice.internal.handler;

import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.energidataservice.internal.ApiController;
import org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants;
import org.openhab.binding.energidataservice.internal.PriceListParser;
import org.openhab.binding.energidataservice.internal.action.EnergiDataServiceActions;
import org.openhab.binding.energidataservice.internal.api.ChargeType;
import org.openhab.binding.energidataservice.internal.api.ChargeTypeCode;
import org.openhab.binding.energidataservice.internal.api.DatahubTariffFilter;
import org.openhab.binding.energidataservice.internal.api.DatahubTariffFilterFactory;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameter;
import org.openhab.binding.energidataservice.internal.api.GlobalLocationNumber;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecord;
import org.openhab.binding.energidataservice.internal.api.dto.ElspotpriceRecord;
import org.openhab.binding.energidataservice.internal.api.dto.ForecastpriceRecord;
import org.openhab.binding.energidataservice.internal.config.CarnotForecastDataServiceConfiguration;
import org.openhab.binding.energidataservice.internal.config.DatahubPriceConfiguration;
import org.openhab.binding.energidataservice.internal.config.EnergiDataServiceConfiguration;
import org.openhab.binding.energidataservice.internal.config.PriceConfiguration;
import org.openhab.binding.energidataservice.internal.exception.DataServiceException;
import org.openhab.binding.energidataservice.internal.retry.RetryPolicyFactory;
import org.openhab.binding.energidataservice.internal.retry.RetryStrategy;
import org.openhab.core.i18n.LocaleProvider;
import org.openhab.core.i18n.TimeZoneProvider;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link EnergiDataServiceHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class EnergiDataServiceHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(EnergiDataServiceHandler.class);
    private final LocaleProvider localeProvider;
    private final TimeZoneProvider timeZoneProvider;
    private final ApiController apiController;
    private final PriceListParser priceListParser = new PriceListParser();
    private final Gson gson = new Gson();

    private EnergiDataServiceConfiguration config;
    private CarnotForecastDataServiceConfiguration carnotConfig;
    private RetryStrategy retryPolicy = RetryPolicyFactory.initial();
    private Collection<DatahubPricelistRecord> netTariffRecords = new ArrayList<>();
    private Collection<DatahubPricelistRecord> systemTariffRecords = new ArrayList<>();
    private Collection<DatahubPricelistRecord> electricityTaxRecords = new ArrayList<>();
    private Collection<DatahubPricelistRecord> transmissionNetTariffRecords = new ArrayList<>();
    private Map<Instant, BigDecimal> spotPriceMap = new ConcurrentHashMap<>();
    private Map<Instant, BigDecimal> forecastPriceMap = new ConcurrentHashMap<>();
    private Map<Instant, BigDecimal> netTariffMap = new ConcurrentHashMap<>();
    private Map<Instant, BigDecimal> systemTariffMap = new ConcurrentHashMap<>();
    private Map<Instant, BigDecimal> electricityTaxMap = new ConcurrentHashMap<>();
    private Map<Instant, BigDecimal> transmissionNetTariffMap = new ConcurrentHashMap<>();
    private @Nullable ScheduledFuture<?> refreshFuture;
    private @Nullable ScheduledFuture<?> priceUpdateFuture;
    private @Nullable ScheduledFuture<?> forecastUpdateFuture;

    private record Price(String hourStart, BigDecimal spotPrice, String spotPriceCurrency,
            @Nullable BigDecimal netTariff, @Nullable BigDecimal systemTariff, @Nullable BigDecimal electricityTax,
            @Nullable BigDecimal transmissionNetTariff) {
    }

    public EnergiDataServiceHandler(Thing thing, HttpClient httpClient, LocaleProvider localeProvider,
            TimeZoneProvider timeZoneProvider) {
        super(thing);
        this.localeProvider = localeProvider;
        this.timeZoneProvider = timeZoneProvider;
        this.apiController = new ApiController(httpClient, timeZoneProvider);

        // Default configuration
        this.config = new EnergiDataServiceConfiguration();
        this.carnotConfig = new CarnotForecastDataServiceConfiguration();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (!(command instanceof RefreshType)) {
            return;
        }

        if (ELECTRICITY_CHANNELS.contains(channelUID.getId())) {
            refreshElectricityPrices();
        }
    }

    @Override
    public void initialize() {
        config = getConfigAs(EnergiDataServiceConfiguration.class);
        carnotConfig = getConfigAs(CarnotForecastDataServiceConfiguration.class);

        if (config.priceArea.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.no-price-area");
            return;
        }
        GlobalLocationNumber gln = config.getGridCompanyGLN();
        if (!gln.isEmpty() && !gln.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-grid-company-gln");
            return;
        }
        gln = config.getEnerginetGLN();
        if (!gln.isEmpty() && !gln.isValid()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "@text/offline.conf-error.invalid-energinet-gln");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        refreshFuture = scheduler.schedule(this::refreshElectricityPrices, 0, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> refreshFuture = this.refreshFuture;
        if (refreshFuture != null) {
            refreshFuture.cancel(true);
            this.refreshFuture = null;
        }
        ScheduledFuture<?> priceUpdateFuture = this.priceUpdateFuture;
        if (priceUpdateFuture != null) {
            priceUpdateFuture.cancel(true);
            this.priceUpdateFuture = null;
        }

        ScheduledFuture<?> forecastUpdateFuture = this.forecastUpdateFuture;
        if (forecastUpdateFuture != null) {
            forecastUpdateFuture.cancel(true);
            this.forecastUpdateFuture = null;
        }
        spotPriceMap.clear();
        forecastPriceMap.clear();
        netTariffMap.clear();
        systemTariffMap.clear();
        electricityTaxMap.clear();
        transmissionNetTariffMap.clear();

        netTariffRecords.clear();
        systemTariffRecords.clear();
        electricityTaxRecords.clear();
        transmissionNetTariffRecords.clear();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(EnergiDataServiceActions.class);
    }

    private void refreshElectricityPrices() {
        RetryStrategy retryPolicy;
        try {
            Map<String, String> properties = editProperties();

            ElspotpriceRecord[] spotPriceRecords = (isLinked(CHANNEL_CURRENT_SPOT_PRICE)
                    || isLinked(CHANNEL_FUTURE_PRICES))
                            ? apiController.getSpotPrices(config.priceArea, config.getCurrency(), properties)
                            : new ElspotpriceRecord[0];

            updateProperties(properties);

            if (!downloadPriceListsIfLinked(netTariffRecords, CHANNEL_CURRENT_NET_TARIFF, config.getGridCompanyGLN(),
                    getNetTariffFilter())) {
                logger.debug("Cached net tariffs still valid, skipping download.");
            }

            if (!downloadPriceListsIfLinked(systemTariffRecords, CHANNEL_CURRENT_SYSTEM_TARIFF,
                    config.getEnerginetGLN(), DatahubTariffFilterFactory.getSystemTariff())) {
                logger.debug("Cached system tariffs still valid, skipping download.");
            }

            if (!downloadPriceListsIfLinked(electricityTaxRecords, CHANNEL_CURRENT_ELECTRICITY_TAX,
                    config.getEnerginetGLN(), DatahubTariffFilterFactory.getElectricityTax())) {
                logger.debug("Cached electricity taxes still valid, skipping download.");
            }

            if (!downloadPriceListsIfLinked(transmissionNetTariffRecords, CHANNEL_CURRENT_TRANSMISSION_NET_TARIFF,
                    config.getEnerginetGLN(), DatahubTariffFilterFactory.getTransmissionNetTariff())) {
                logger.debug("Cached transmission net tariffs still valid, skipping download.");
            }

            updateStatus(ThingStatus.ONLINE);

            processSpotPrices(spotPriceRecords);
            netTariffMap = priceListParser.toHourly(netTariffRecords);
            systemTariffMap = priceListParser.toHourly(systemTariffRecords);
            electricityTaxMap = priceListParser.toHourly(electricityTaxRecords);
            transmissionNetTariffMap = priceListParser.toHourly(transmissionNetTariffRecords);
            updatePrices();

            if (isLinked(CHANNEL_CURRENT_SPOT_PRICE) || isLinked(CHANNEL_FUTURE_PRICES)) {
                if (spotPriceRecords.length < 13) {
                    retryPolicy = RetryPolicyFactory.whenExpectedSpotPriceDataMissing(DAILY_REFRESH_TIME_CET,
                            NORD_POOL_TIMEZONE);
                } else {
                    retryPolicy = RetryPolicyFactory.atFixedTime(DAILY_REFRESH_TIME_CET, NORD_POOL_TIMEZONE);
                }
            } else {
                retryPolicy = RetryPolicyFactory.atFixedTime(LocalTime.MIDNIGHT, timeZoneProvider.getTimeZone());
            }
        } catch (DataServiceException e) {
            if (e.getHttpStatus() != 0) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                        HttpStatus.getCode(e.getHttpStatus()).getMessage());
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            }
            if (e.getCause() != null) {
                logger.debug("Error retrieving prices", e);
            }
            retryPolicy = RetryPolicyFactory.fromThrowable(e);
        } catch (InterruptedException e) {
            logger.debug("Refresh job interrupted");
            Thread.currentThread().interrupt();
            return;
        }

        rescheduleRefreshJob(retryPolicy);
    }

    private boolean downloadPriceListsIfLinked(Collection<DatahubPricelistRecord> records, String channelId,
            GlobalLocationNumber globalLocationNumber, DatahubTariffFilter filter)
            throws InterruptedException, DataServiceException {
        if (!isLinked(channelId) && !isLinked(CHANNEL_FUTURE_PRICES)) {
            return true;
        }
        return downloadPriceLists(records, globalLocationNumber, filter);
    }

    private boolean downloadPriceLists(Collection<DatahubPricelistRecord> records,
            GlobalLocationNumber globalLocationNumber, DatahubTariffFilter filter)
            throws InterruptedException, DataServiceException {
        LocalDateTime localHourStart = LocalDateTime.now(EnergiDataServiceBindingConstants.DATAHUB_TIMEZONE)
                .truncatedTo(ChronoUnit.HOURS);
        LocalDateTime localMidnight = localHourStart.plusDays(1).truncatedTo(ChronoUnit.DAYS);

        if (records.stream().anyMatch(r -> r.validTo().isAfter(localMidnight))) {
            return false;
        }

        if (globalLocationNumber.isEmpty()) {
            return true;
        }
        Map<String, String> properties = editProperties();
        records.clear();
        records.addAll(apiController.getDatahubPriceLists(globalLocationNumber, ChargeType.Tariff, filter, properties)
                .stream().filter(r -> !r.validTo().isBefore(localHourStart)).toList());
        updateProperties(properties);

        return true;
    }

    private DatahubTariffFilter getNetTariffFilter() {
        Channel channel = getThing().getChannel(CHANNEL_CURRENT_NET_TARIFF);
        if (channel == null) {
            return DatahubTariffFilterFactory.getNetTariffByGLN(config.gridCompanyGLN);
        }

        DatahubPriceConfiguration datahubPriceConfiguration = channel.getConfiguration()
                .as(DatahubPriceConfiguration.class);

        if (!datahubPriceConfiguration.hasAnyFilterOverrides()) {
            return DatahubTariffFilterFactory.getNetTariffByGLN(config.gridCompanyGLN);
        }

        DateQueryParameter start = datahubPriceConfiguration.getStart();
        if (start == null) {
            logger.warn("Invalid channel configuration parameter 'start': {}", datahubPriceConfiguration.start);
            return DatahubTariffFilterFactory.getNetTariffByGLN(config.gridCompanyGLN);
        }

        Set<ChargeTypeCode> chargeTypeCodes = datahubPriceConfiguration.getChargeTypeCodes();
        Set<String> notes = datahubPriceConfiguration.getNotes();
        if (!chargeTypeCodes.isEmpty() || !notes.isEmpty()) {
            // Completely override filter.
            return new DatahubTariffFilter(chargeTypeCodes, notes, start);
        } else {
            // Only override start date in pre-configured filter.
            return new DatahubTariffFilter(DatahubTariffFilterFactory.getNetTariffByGLN(config.gridCompanyGLN), start);
        }
    }

    private void processSpotPrices(ElspotpriceRecord[] records) {
        spotPriceMap.clear();
        boolean isDKK = config.getCurrency().equals(EnergiDataServiceBindingConstants.CURRENCY_DKK);
        for (ElspotpriceRecord record : records) {
            spotPriceMap.put(record.hour(),
                    (isDKK ? record.spotPriceDKK() : record.spotPriceEUR()).divide(BigDecimal.valueOf(1000)));
        }
    }

    private void processForecastPrices(ForecastpriceRecord[] records) {
        forecastPriceMap.clear();
        boolean isDKK = config.getCurrency().equals(EnergiDataServiceBindingConstants.CURRENCY_DKK);
        for (ForecastpriceRecord record : records) {
            spotPriceMap.put(record.hour(),
                    (isDKK ? record.forecastPriceDKK() : record.forecastPriceEUR()).divide(BigDecimal.valueOf(1000)));
        }
    }

    private void updatePrices() {
        removeHistoricPrices();

        updateCurrentSpotPrice();
        updateCurrentTariff(CHANNEL_CURRENT_NET_TARIFF, netTariffMap);
        updateCurrentTariff(CHANNEL_CURRENT_SYSTEM_TARIFF, systemTariffMap);
        updateCurrentTariff(CHANNEL_CURRENT_ELECTRICITY_TAX, electricityTaxMap);
        updateCurrentTariff(CHANNEL_CURRENT_TRANSMISSION_NET_TARIFF, transmissionNetTariffMap);
        updateFuturePrices();
        updateForecastPrices();

        reschedulePriceUpdateJob();
    }

    private void removeHistoricPrices() {
        Instant currentHourStart = Instant.now().truncatedTo(ChronoUnit.HOURS);

        spotPriceMap.entrySet().removeIf(entry -> entry.getKey().isBefore(currentHourStart));
        netTariffMap.entrySet().removeIf(entry -> entry.getKey().isBefore(currentHourStart));
        systemTariffMap.entrySet().removeIf(entry -> entry.getKey().isBefore(currentHourStart));
        electricityTaxMap.entrySet().removeIf(entry -> entry.getKey().isBefore(currentHourStart));
        transmissionNetTariffMap.entrySet().removeIf(entry -> entry.getKey().isBefore(currentHourStart));
    }

    private void updateCurrentSpotPrice() {
        if (!isLinked(CHANNEL_CURRENT_SPOT_PRICE)) {
            return;
        }
        Instant hourStart = Instant.now().truncatedTo(ChronoUnit.HOURS);
        BigDecimal price = getVATAdjustedPrice(spotPriceMap.get(hourStart), CHANNEL_CURRENT_SPOT_PRICE);
        updateState(CHANNEL_CURRENT_SPOT_PRICE, price != null ? new DecimalType(price) : UnDefType.UNDEF);
    }

    private void updateCurrentTariff(String channelId, Map<Instant, BigDecimal> tariffMap) {
        if (!isLinked(channelId)) {
            return;
        }
        Instant hourStart = Instant.now().truncatedTo(ChronoUnit.HOURS);
        BigDecimal price = getVATAdjustedPrice(tariffMap.get(hourStart), channelId);
        updateState(channelId, price != null ? new DecimalType(price) : UnDefType.UNDEF);
    }

    private @Nullable BigDecimal getVATAdjustedPrice(@Nullable BigDecimal price, String channelId) {
        if (price == null) {
            return price;
        }
        Channel channel = getThing().getChannel(channelId);
        if (channel == null) {
            return price;
        }
        Object obj = channel.getConfiguration().get(PriceConfiguration.INCLUDE_VAT);
        if (obj == null) {
            return price;
        }
        Boolean includeVAT = (Boolean) obj;
        if (includeVAT) {
            return price.multiply(getVATPercentageFactor());
        }
        return price;
    }

    private BigDecimal getVATPercentageFactor() {
        String country = localeProvider.getLocale().getCountry();
        switch (country) {
            case "DK":
            case "NO":
            case "SE":
                return new BigDecimal("1.25");
            case "DE":
                return new BigDecimal("1.19");
            default:
                logger.debug("No VAT rate for country {}", country);
                return BigDecimal.ONE;
        }
    }

    private void updateForecastPrices() {
        if (!isLinked(CHANNEL_FORECAST_PRICES)) {
            return;
        }

        try {
            ForecastpriceRecord[] forecastpriceRecords = apiController.getForecastPrices(config.priceArea, "spotprice",
                    7, carnotConfig.userName, carnotConfig.apiKey);
            processForecastPrices(forecastpriceRecords);

        } catch (DataServiceException e) {
            if (e.getHttpStatus() != 0) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                        HttpStatus.getCode(e.getHttpStatus()).getMessage());
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            }
            if (e.getCause() != null) {
                logger.debug("Error retrieving prices", e);
            }
            retryPolicy = RetryPolicyFactory.fromThrowable(e);
        } catch (InterruptedException e) {
            logger.debug("Refresh job interrupted");
            Thread.currentThread().interrupt();
            return;
        }
    }

    private void updateFuturePrices() {
        if (!isLinked(CHANNEL_FUTURE_PRICES)) {
            return;
        }
        Price[] targetPrices = new Price[spotPriceMap.size()];
        List<Entry<Instant, BigDecimal>> sourcePrices = spotPriceMap.entrySet().stream()
                .sorted(Map.Entry.<Instant, BigDecimal> comparingByKey()).toList();

        int i = 0;
        for (Entry<Instant, BigDecimal> sourcePrice : sourcePrices) {
            Instant hourStart = sourcePrice.getKey();
            BigDecimal netTariff = netTariffMap.get(hourStart);
            BigDecimal systemTariff = systemTariffMap.get(hourStart);
            BigDecimal electricityTax = electricityTaxMap.get(hourStart);
            BigDecimal transmissionNetTariff = transmissionNetTariffMap.get(hourStart);
            targetPrices[i++] = new Price(hourStart.toString(), sourcePrice.getValue(), config.currencyCode, netTariff,
                    systemTariff, electricityTax, transmissionNetTariff);
        }
        updateState(CHANNEL_FUTURE_PRICES, new StringType(gson.toJson(targetPrices)));
    }

    public Currency getCurrency() {
        return config.getCurrency();
    }

    /**
     * Return cached spot prices or try once to download them if not cached
     * (usually if no items are linked).
     *
     * @return Map of future spot prices
     */
    public Map<Instant, BigDecimal> getSpotPrices() {
        if (spotPriceMap.isEmpty()) {
            try {
                Map<String, String> properties = editProperties();
                ElspotpriceRecord[] spotPriceRecords = apiController.getSpotPrices(config.priceArea,
                        config.getCurrency(), properties);
                updateProperties(properties);
                processSpotPrices(spotPriceRecords);
                removeHistoricPrices();
            } catch (DataServiceException e) {
                logger.warn("Error retrieving spot prices");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new HashMap<>(spotPriceMap);
    }

    /**
     * Return cached net tariffs or try once to download them if not cached
     * (usually if no items are linked).
     *
     * @return Map of future net tariffs
     */
    public Map<Instant, BigDecimal> getNetTariffs() {
        if (netTariffMap.isEmpty()) {
            try {
                downloadPriceLists(netTariffRecords, config.getGridCompanyGLN(), getNetTariffFilter());
                netTariffMap = priceListParser.toHourly(netTariffRecords);
                removeHistoricPrices();
            } catch (DataServiceException e) {
                logger.warn("Error retrieving net tariffs");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new HashMap<>(netTariffMap);
    }

    /**
     * Return cached system tariffs or try once to download them if not cached
     * (usually if no items are linked).
     *
     * @return Map of future system tariffs
     */
    public Map<Instant, BigDecimal> getSystemTariffs() {
        if (systemTariffMap.isEmpty()) {
            try {
                downloadPriceLists(systemTariffRecords, config.getEnerginetGLN(),
                        DatahubTariffFilterFactory.getSystemTariff());
                systemTariffMap = priceListParser.toHourly(systemTariffRecords);
                removeHistoricPrices();
            } catch (DataServiceException e) {
                logger.warn("Error retrieving system tariffs");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new HashMap<>(systemTariffMap);
    }

    /**
     * Return cached electricity taxes or try once to download them if not cached
     * (usually if no items are linked).
     *
     * @return Map of future electricity taxes
     */
    public Map<Instant, BigDecimal> getElectricityTaxes() {
        if (electricityTaxMap.isEmpty()) {
            try {
                downloadPriceLists(electricityTaxRecords, config.getEnerginetGLN(),
                        DatahubTariffFilterFactory.getElectricityTax());
                electricityTaxMap = priceListParser.toHourly(electricityTaxRecords);
                removeHistoricPrices();
            } catch (DataServiceException e) {
                logger.warn("Error retrieving electricity taxes");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new HashMap<>(electricityTaxMap);
    }

    /**
     * Return cached transmission net tariffs or try once to download them if not cached
     * (usually if no items are linked).
     *
     * @return Map of future transmissions net tariffs
     */
    public Map<Instant, BigDecimal> getTransmissionNetTariffs() {
        if (transmissionNetTariffMap.isEmpty()) {
            try {
                downloadPriceLists(transmissionNetTariffRecords, config.getEnerginetGLN(),
                        DatahubTariffFilterFactory.getTransmissionNetTariff());
                transmissionNetTariffMap = priceListParser.toHourly(transmissionNetTariffRecords);
                removeHistoricPrices();
            } catch (DataServiceException e) {
                logger.warn("Error retrieving transmission net tariffs");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return new HashMap<>(transmissionNetTariffMap);
    }

    private void reschedulePriceUpdateJob() {
        ScheduledFuture<?> priceUpdateJob = this.priceUpdateFuture;
        if (priceUpdateJob != null) {
            // Do not interrupt ourselves.
            priceUpdateJob.cancel(false);
            this.priceUpdateFuture = null;
        }

        Instant now = Instant.now();
        long millisUntilNextClockHour = Duration
                .between(now, now.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)).toMillis() + 1;
        this.priceUpdateFuture = scheduler.schedule(this::updatePrices, millisUntilNextClockHour,
                TimeUnit.MILLISECONDS);
        logger.debug("Price update job rescheduled in {} milliseconds", millisUntilNextClockHour);
    }

    private void rescheduleRefreshJob(RetryStrategy retryPolicy) {
        // Preserve state of previous retry policy when configuration is the same.
        if (!retryPolicy.equals(this.retryPolicy)) {
            this.retryPolicy = retryPolicy;
        }

        ScheduledFuture<?> refreshJob = this.refreshFuture;

        long secondsUntilNextRefresh = this.retryPolicy.getDuration().getSeconds();
        Instant timeOfNextRefresh = Instant.now().plusSeconds(secondsUntilNextRefresh);
        this.refreshFuture = scheduler.schedule(this::refreshElectricityPrices, secondsUntilNextRefresh,
                TimeUnit.SECONDS);

        logger.debug("Refresh job rescheduled in {} seconds: {}", secondsUntilNextRefresh, timeOfNextRefresh);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(PROPERTY_DATETIME_FORMAT);
        updateProperty(PROPERTY_NEXT_CALL, LocalDateTime.ofInstant(timeOfNextRefresh, timeZoneProvider.getTimeZone())
                .truncatedTo(ChronoUnit.SECONDS).format(formatter));

        if (refreshJob != null) {
            refreshJob.cancel(true);
        }
    }
}
