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

import static org.openhab.binding.energidataservice.internal.EnergiDataServiceBindingConstants.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.openhab.binding.energidataservice.internal.api.ChargeType;
import org.openhab.binding.energidataservice.internal.api.DatahubTariffFilter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameter;
import org.openhab.binding.energidataservice.internal.api.DateQueryParameterType;
import org.openhab.binding.energidataservice.internal.api.GlobalLocationNumber;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecord;
import org.openhab.binding.energidataservice.internal.api.dto.DatahubPricelistRecords;
import org.openhab.binding.energidataservice.internal.api.dto.ElspotpriceRecord;
import org.openhab.binding.energidataservice.internal.api.dto.ElspotpriceRecords;
import org.openhab.binding.energidataservice.internal.api.dto.ForecastpriceRecord;
import org.openhab.binding.energidataservice.internal.api.dto.ForecastpriceRecords;
import org.openhab.binding.energidataservice.internal.api.serialization.InstantDeserializer;
import org.openhab.binding.energidataservice.internal.api.serialization.LocalDateTimeDeserializer;
import org.openhab.binding.energidataservice.internal.exception.DataServiceException;
import org.openhab.core.i18n.TimeZoneProvider;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/**
 * The {@link ApiController} is responsible for interacting with Energi Data Service.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class ApiController {
    private static final String ENDPOINT = "https://api.energidataservice.dk/";
    private static final String DATASET_PATH = "dataset/";

    private static final String FORECAST_ENDPOINT = "https://openapi.carnot.dk/openapi/get_predict";

    private static final String DATASET_NAME_SPOT_PRICES = "Elspotprices";
    private static final String DATASET_NAME_DATAHUB_PRICELIST = "DatahubPricelist";

    private static final String FILTER_KEY_PRICE_AREA = "PriceArea";
    private static final String FILTER_KEY_CHARGE_TYPE = "ChargeType";
    private static final String FILTER_KEY_CHARGE_TYPE_CODE = "ChargeTypeCode";
    private static final String FILTER_KEY_GLN_NUMBER = "GLN_Number";
    private static final String FILTER_KEY_NOTE = "Note";

    private static final String HEADER_REMAINING_CALLS = "RemainingCalls";
    private static final String HEADER_TOTAL_CALLS = "TotalCalls";

    private final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private final Gson gson = new GsonBuilder() //
            .registerTypeAdapter(Instant.class, new InstantDeserializer()) //
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeDeserializer()) //
            .create();
    private final HttpClient httpClient;
    private final TimeZoneProvider timeZoneProvider;
    private final String userAgent;

    public ApiController(HttpClient httpClient, TimeZoneProvider timeZoneProvider) {
        this.httpClient = httpClient;
        this.timeZoneProvider = timeZoneProvider;
        userAgent = "openHAB " + FrameworkUtil.getBundle(this.getClass()).getVersion().toString();
    }

    /**
     * Retrieve forecast prices for the numbers of days given by days ahead
     *
     * @param
     */
    public ForecastpriceRecord[] getForecastPrices(String PriceArea, String energySource, Integer DaysAhead,
            String userName, String apiKey) throws InterruptedException, DataServiceException {
        Request request = httpClient.newRequest(FORECAST_ENDPOINT).param("energysource", energySource) //
                .param("region", PriceArea) //
                .param("daysahead", DaysAhead.toString()) //
                .header(userName, apiKey) //
                .agent(userAgent) //
                .method(HttpMethod.GET);

        logger.trace("GET request for {}", request.getURI());

        try {
            ContentResponse response = request.send();

            int status = response.getStatus();
            if (!HttpStatus.isSuccess(status)) {
                throw new DataServiceException("The request failed with HTTP error " + status, status);
            }
            String responseContent = response.getContentAsString();
            if (responseContent.isEmpty()) {
                throw new DataServiceException("Empty response");
            }
            logger.trace("Response content: '{}'", responseContent);

            ForecastpriceRecords records = gson.fromJson(responseContent, ForecastpriceRecords.class);
            if (records == null) {
                throw new DataServiceException("Error parsing response");
            }

            if (records.total() == 0 || records.records().length == 0) {
                throw new DataServiceException("No records");
            }

            return Arrays.stream(records.records()).filter(Objects::nonNull).toArray(ForecastpriceRecord[]::new);
        } catch (JsonSyntaxException e) {
            throw new DataServiceException("Error parsing response", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new DataServiceException(e);
        }
    }

    /**
     * Retrieve spot prices for requested area and in requested {@link Currency}.
     *
     * @param priceArea Usually DK1 or DK2
     * @param currency DKK or EUR
     * @param properties Map of properties which will be updated with metadata from headers
     * @return Records with pairs of hour start and price in requested currency.
     * @throws InterruptedException
     * @throws DataServiceException
     */
    public ElspotpriceRecord[] getSpotPrices(String priceArea, Currency currency, Map<String, String> properties)
            throws InterruptedException, DataServiceException {
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("Invalid currency " + currency.getCurrencyCode());
        }

        Request request = httpClient.newRequest(ENDPOINT + DATASET_PATH + DATASET_NAME_SPOT_PRICES)
                .param("start", DateQueryParameterType.UTC_NOW.toString()) //
                .param("filter", "{\"" + FILTER_KEY_PRICE_AREA + "\":\"" + priceArea + "\"}") //
                .param("columns", "HourUTC,SpotPrice" + currency) //
                .agent(userAgent) //
                .method(HttpMethod.GET);

        logger.trace("GET request for {}", request.getURI());

        try {
            ContentResponse response = request.send();

            updatePropertiesFromResponse(response, properties);

            int status = response.getStatus();
            if (!HttpStatus.isSuccess(status)) {
                throw new DataServiceException("The request failed with HTTP error " + status, status);
            }
            String responseContent = response.getContentAsString();
            if (responseContent.isEmpty()) {
                throw new DataServiceException("Empty response");
            }
            logger.trace("Response content: '{}'", responseContent);

            ElspotpriceRecords records = gson.fromJson(responseContent, ElspotpriceRecords.class);
            if (records == null) {
                throw new DataServiceException("Error parsing response");
            }

            if (records.total() == 0 || records.records().length == 0) {
                throw new DataServiceException("No records");
            }

            return Arrays.stream(records.records()).filter(Objects::nonNull).toArray(ElspotpriceRecord[]::new);
        } catch (JsonSyntaxException e) {
            throw new DataServiceException("Error parsing response", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new DataServiceException(e);
        }
    }

    private void updatePropertiesFromResponse(ContentResponse response, Map<String, String> properties) {
        HttpFields headers = response.getHeaders();
        String remainingCalls = headers.get(HEADER_REMAINING_CALLS);
        if (remainingCalls != null) {
            properties.put(PROPERTY_REMAINING_CALLS, remainingCalls);
        }
        String totalCalls = headers.get(HEADER_TOTAL_CALLS);
        if (totalCalls != null) {
            properties.put(PROPERTY_TOTAL_CALLS, totalCalls);
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(PROPERTY_DATETIME_FORMAT);
        properties.put(PROPERTY_LAST_CALL, LocalDateTime.now(timeZoneProvider.getTimeZone()).format(formatter));
    }

    /**
     * Retrieve datahub pricelists for requested GLN and charge type/charge type code.
     *
     * @param globalLocationNumber Global Location Number of the Charge Owner
     * @param chargeType Charge type (Subscription, Fee or Tariff).
     * @param startDate Specifies the start point of the period for the data request
     * @param tariffFilter Tariff filter (charge type codes and notes).
     * @param properties Map of properties which will be updated with metadata from headers
     * @return Price list for requested GLN and note.
     * @throws InterruptedException
     * @throws DataServiceException
     */
    public Collection<DatahubPricelistRecord> getDatahubPriceLists(GlobalLocationNumber globalLocationNumber,
            ChargeType chargeType, DatahubTariffFilter tariffFilter, Map<String, String> properties)
            throws InterruptedException, DataServiceException {
        String columns = "ValidFrom,ValidTo,ChargeTypeCode";
        for (int i = 1; i < 25; i++) {
            columns += ",Price" + i;
        }

        Map<String, Collection<String>> filterMap = new HashMap<>(Map.of( //
                FILTER_KEY_GLN_NUMBER, List.of(globalLocationNumber.toString()), //
                FILTER_KEY_CHARGE_TYPE, List.of(chargeType.toString())));

        Collection<String> chargeTypeCodes = tariffFilter.getChargeTypeCodesAsStrings();
        if (!chargeTypeCodes.isEmpty()) {
            filterMap.put(FILTER_KEY_CHARGE_TYPE_CODE, chargeTypeCodes);
        }

        Collection<String> notes = tariffFilter.getNotes();
        if (!notes.isEmpty()) {
            filterMap.put(FILTER_KEY_NOTE, notes);
        }

        Request request = httpClient.newRequest(ENDPOINT + DATASET_PATH + DATASET_NAME_DATAHUB_PRICELIST)
                .param("filter", mapToFilter(filterMap)) //
                .param("columns", columns) //
                .agent(userAgent) //
                .method(HttpMethod.GET);

        DateQueryParameter dateQueryParameter = tariffFilter.getDateQueryParameter();
        if (!dateQueryParameter.isEmpty()) {
            request = request.param("start", dateQueryParameter.toString());
        }

        logger.trace("GET request for {}", request.getURI());

        try {
            ContentResponse response = request.send();

            updatePropertiesFromResponse(response, properties);

            int status = response.getStatus();
            if (!HttpStatus.isSuccess(status)) {
                throw new DataServiceException("The request failed with HTTP error " + status, status);
            }
            String responseContent = response.getContentAsString();
            if (responseContent.isEmpty()) {
                throw new DataServiceException("Empty response");
            }
            logger.trace("Response content: '{}'", responseContent);

            DatahubPricelistRecords records = gson.fromJson(responseContent, DatahubPricelistRecords.class);
            if (records == null) {
                throw new DataServiceException("Error parsing response");
            }

            if (records.limit() > 0 && records.limit() < records.total()) {
                logger.warn("{} price list records available, but only {} returned.", records.total(), records.limit());
            }

            return Arrays.stream(records.records()).filter(Objects::nonNull).toList();
        } catch (JsonSyntaxException e) {
            throw new DataServiceException("Error parsing response", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new DataServiceException(e);
        }
    }

    private String mapToFilter(Map<String, Collection<String>> map) {
        return "{" + map.entrySet().stream().map(
                e -> "\"" + e.getKey() + "\":[\"" + e.getValue().stream().collect(Collectors.joining("\",\"")) + "\"]")
                .collect(Collectors.joining(",")) + "}";
    }
}
