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
package org.openhab.binding.boschshc.internal.devices.bridge;

import static org.eclipse.jetty.http.HttpMethod.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.openhab.binding.boschshc.internal.devices.BoschSHCHandler;
import org.openhab.binding.boschshc.internal.devices.bridge.dto.Device;
import org.openhab.binding.boschshc.internal.devices.bridge.dto.DeviceServiceData;
import org.openhab.binding.boschshc.internal.devices.bridge.dto.LongPollResult;
import org.openhab.binding.boschshc.internal.devices.bridge.dto.Room;
import org.openhab.binding.boschshc.internal.discovery.ThingDiscoveryService;
import org.openhab.binding.boschshc.internal.exceptions.BoschSHCException;
import org.openhab.binding.boschshc.internal.exceptions.LongPollingFailedException;
import org.openhab.binding.boschshc.internal.exceptions.PairingFailedException;
import org.openhab.binding.boschshc.internal.services.dto.BoschSHCServiceState;
import org.openhab.binding.boschshc.internal.services.dto.JsonRestExceptionResponse;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

/**
 * Representation of a connection with a Bosch Smart Home Controller bridge.
 *
 * @author Stefan Kästle - Initial contribution
 * @author Gerd Zanker - added HttpClient with pairing support
 * @author Christian Oeing - refactorings of e.g. server registration
 * @author David Pace - Added support for custom endpoints and HTTP POST requests
 * @author Gerd Zanker - added thing discovery
 */
@NonNullByDefault
public class BridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(BridgeHandler.class);

    /**
     * gson instance to convert a class to json string and back.
     */
    private final Gson gson = new Gson();

    /**
     * Handler to do long polling.
     */
    private final LongPolling longPolling;

    /**
     * HTTP client for all communications to and from the bridge.
     * <p>
     * This member is package-protected to enable mocking in unit tests.
     */
    /* package */ @Nullable
    BoschHttpClient httpClient;

    private @Nullable ScheduledFuture<?> scheduledPairing;

    /**
     * SHC thing/device discovery service instance.
     * Registered and unregistered if service is actived/deactived.
     * Used to scan for things after bridge is paired with SHC.
     */
    private @Nullable ThingDiscoveryService thingDiscoveryService;

    public BridgeHandler(Bridge bridge) {
        super(bridge);

        this.longPolling = new LongPolling(this.scheduler, this::handleLongPollResult, this::handleLongPollFailure);
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(ThingDiscoveryService.class);
    }

    @Override
    public void initialize() {
        Bundle bundle = FrameworkUtil.getBundle(getClass());
        if (bundle != null) {
            logger.debug("Initialize {} Version {}", bundle.getSymbolicName(), bundle.getVersion());
        }

        // Read configuration
        BridgeConfiguration config = getConfigAs(BridgeConfiguration.class);

        String ipAddress = config.ipAddress.trim();
        if (ipAddress.isEmpty()) {
            this.updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-empty-ip");
            return;
        }

        String password = config.password.trim();
        if (password.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-empty-password");
            return;
        }

        SslContextFactory factory;
        try {
            // prepare SSL key and certificates
            factory = new BoschSslUtil(ipAddress).getSslContextFactory();
        } catch (PairingFailedException e) {
            this.updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-ssl");
            return;
        }

        // Instantiate HttpClient with the SslContextFactory
        BoschHttpClient httpClient = this.httpClient = new BoschHttpClient(ipAddress, password, factory);

        // Start http client
        try {
            httpClient.start();
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                    String.format("Could not create http connection to controller: %s", e.getMessage()));
            return;
        }

        // general checks are OK, therefore set the status to unknown and wait for initial access
        this.updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.UNKNOWN.NONE);

        // Initialize bridge in the background.
        // Start initial access the first time
        scheduleInitialAccess(httpClient);
    }

    @Override
    public void dispose() {
        // Cancel scheduled pairing.
        @Nullable
        ScheduledFuture<?> scheduledPairing = this.scheduledPairing;
        if (scheduledPairing != null) {
            scheduledPairing.cancel(true);
            this.scheduledPairing = null;
        }

        // Stop long polling.
        this.longPolling.stop();

        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient != null) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                logger.debug("HttpClient failed on bridge disposal: {}", e.getMessage());
            }
            this.httpClient = null;
        }

        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    /**
     * Schedule the initial access.
     * Use a delay if pairing fails and next retry is scheduled.
     */
    private void scheduleInitialAccess(BoschHttpClient httpClient) {
        this.scheduledPairing = scheduler.schedule(() -> initialAccess(httpClient), 15, TimeUnit.SECONDS);
    }

    /**
     * Execute the initial access.
     * Uses the HTTP Bosch SHC client
     * to check if access if possible
     * pairs this Bosch SHC Bridge with the SHC if necessary
     * and starts the first log poll.
     * <p>
     * This method is package-protected to enable unit testing.
     */
    /* package */ void initialAccess(BoschHttpClient httpClient) {
        logger.debug("Initializing Bosch SHC Bridge: {} - HTTP client is: {}", this, httpClient);

        try {
            // check if SCH is offline
            if (!httpClient.isOnline()) {
                // update status already if access is not possible
                this.updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.UNKNOWN.NONE,
                        "@text/offline.conf-error-offline");
                // restart later initial access
                scheduleInitialAccess(httpClient);
                return;
            }

            // SHC is online
            // check if SHC access is not possible and pairing necessary
            if (!httpClient.isAccessPossible()) {
                // update status description to show pairing test
                this.updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.UNKNOWN.NONE,
                        "@text/offline.conf-error-pairing");
                if (!httpClient.doPairing()) {
                    this.updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR,
                            "@text/offline.conf-error-pairing");
                }
                // restart initial access - needed also in case of successful pairing to check access again
                scheduleInitialAccess(httpClient);
                return;
            }

            // SHC is online and access should possible
            if (!checkBridgeAccess()) {
                this.updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                        "@text/offline.not-reachable");
                // restart initial access
                scheduleInitialAccess(httpClient);
                return;
            }

            // do thing discovery after pairing
            final ThingDiscoveryService discovery = thingDiscoveryService;
            if (discovery != null) {
                discovery.doScan();
            }

            // start long polling loop
            this.updateStatus(ThingStatus.ONLINE);
            try {
                this.longPolling.start(httpClient);
            } catch (LongPollingFailedException e) {
                this.handleLongPollFailure(e);
            }

        } catch (InterruptedException e) {
            this.updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.UNKNOWN.NONE, "@text/offline.interrupted");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check the bridge access by sending an HTTP request.
     * Does not throw any exception in case the request fails.
     */
    public boolean checkBridgeAccess() throws InterruptedException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;

        if (httpClient == null) {
            return false;
        }

        try {
            logger.debug("Sending http request to BoschSHC to check access: {}", httpClient);
            String url = httpClient.getBoschSmartHomeUrl("devices");
            ContentResponse contentResponse = httpClient.createRequest(url, GET).send();

            // check HTTP status code
            if (!HttpStatus.getCode(contentResponse.getStatus()).isSuccess()) {
                logger.debug("Access check failed with status code: {}", contentResponse.getStatus());
                return false;
            }

            // Access OK
            return true;
        } catch (TimeoutException | ExecutionException e) {
            logger.warn("Access check failed because of {}!", e.getMessage());
            return false;
        }
    }

    /**
     * Get a list of connected devices from the Smart-Home Controller
     *
     * @throws InterruptedException in case bridge is stopped
     */
    public List<Device> getDevices() throws InterruptedException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            return Collections.emptyList();
        }

        try {
            logger.trace("Sending http request to Bosch to request devices: {}", httpClient);
            String url = httpClient.getBoschSmartHomeUrl("devices");
            ContentResponse contentResponse = httpClient.createRequest(url, GET).send();

            // check HTTP status code
            if (!HttpStatus.getCode(contentResponse.getStatus()).isSuccess()) {
                logger.debug("Request devices failed with status code: {}", contentResponse.getStatus());
                return Collections.emptyList();
            }

            String content = contentResponse.getContentAsString();
            logger.trace("Request devices completed with success: {} - status code: {}", content,
                    contentResponse.getStatus());

            Type collectionType = new TypeToken<ArrayList<Device>>() {
            }.getType();
            @Nullable
            List<Device> nullableDevices = gson.fromJson(content, collectionType);
            return Optional.ofNullable(nullableDevices).orElse(Collections.emptyList());
        } catch (TimeoutException | ExecutionException e) {
            logger.debug("Request devices failed because of {}!", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get a list of rooms from the Smart-Home controller
     *
     * @throws InterruptedException in case bridge is stopped
     */
    public List<Room> getRooms() throws InterruptedException {
        List<Room> emptyRooms = new ArrayList<>();
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient != null) {
            try {
                logger.trace("Sending http request to Bosch to request rooms");
                String url = httpClient.getBoschSmartHomeUrl("rooms");
                ContentResponse contentResponse = httpClient.createRequest(url, GET).send();

                // check HTTP status code
                if (!HttpStatus.getCode(contentResponse.getStatus()).isSuccess()) {
                    logger.debug("Request rooms failed with status code: {}", contentResponse.getStatus());
                    return emptyRooms;
                }

                String content = contentResponse.getContentAsString();
                logger.trace("Request rooms completed with success: {} - status code: {}", content,
                        contentResponse.getStatus());

                Type collectionType = new TypeToken<ArrayList<Room>>() {
                }.getType();

                ArrayList<Room> rooms = gson.fromJson(content, collectionType);
                return Objects.requireNonNullElse(rooms, emptyRooms);
            } catch (TimeoutException | ExecutionException e) {
                logger.debug("Request rooms failed because of {}!", e.getMessage());
                return emptyRooms;
            }
        } else {
            return emptyRooms;
        }
    }

    public boolean registerDiscoveryListener(ThingDiscoveryService listener) {
        if (thingDiscoveryService == null) {
            thingDiscoveryService = listener;
            return true;
        }

        return false;
    }

    public boolean unregisterDiscoveryListener() {
        if (thingDiscoveryService != null) {
            thingDiscoveryService = null;
            return true;
        }

        return false;
    }

    /**
     * Bridge callback handler for the results of long polls.
     *
     * It will check the results and
     * forward the received states to the Bosch thing handlers.
     *
     * @param result Results from Long Polling
     */
    private void handleLongPollResult(LongPollResult result) {
        for (DeviceServiceData deviceServiceData : result.result) {
            handleDeviceServiceData(deviceServiceData);
        }
    }

    /**
     * Processes a single long poll result.
     *
     * @param deviceServiceData object representing a single long poll result
     */
    private void handleDeviceServiceData(@Nullable DeviceServiceData deviceServiceData) {
        if (deviceServiceData != null) {
            JsonElement state = obtainState(deviceServiceData);

            logger.debug("Got update for service {} of type {}: {}", deviceServiceData.id, deviceServiceData.type,
                    state);

            var updateDeviceId = deviceServiceData.deviceId;
            if (updateDeviceId == null || state == null) {
                return;
            }

            logger.debug("Got update for device {}", updateDeviceId);

            forwardStateToHandlers(deviceServiceData, state, updateDeviceId);
        }
    }

    /**
     * Extracts the actual state object from the given {@link DeviceServiceData} instance.
     * <p>
     * In some special cases like the <code>BatteryLevel</code> service the {@link DeviceServiceData} object itself
     * contains the state.
     * In all other cases, the state is contained in a sub-object named <code>state</code>.
     *
     * @param deviceServiceData the {@link DeviceServiceData} object from which the state should be obtained
     * @return the state sub-object or the {@link DeviceServiceData} object itself
     */
    @Nullable
    private JsonElement obtainState(DeviceServiceData deviceServiceData) {
        // the battery level service receives no individual state object but rather requires the DeviceServiceData
        // structure
        if ("BatteryLevel".equals(deviceServiceData.id)) {
            return gson.toJsonTree(deviceServiceData);
        }

        return deviceServiceData.state;
    }

    /**
     * Tries to find handlers for the device with the given ID and forwards the received state to the handlers.
     *
     * @param deviceServiceData object representing updates received in long poll results
     * @param state the received state object as JSON element
     * @param updateDeviceId the ID of the device for which the state update was received
     */
    private void forwardStateToHandlers(DeviceServiceData deviceServiceData, JsonElement state, String updateDeviceId) {
        boolean handled = false;

        Bridge bridge = this.getThing();
        for (Thing childThing : bridge.getThings()) {
            // All children of this should implement BoschSHCHandler
            @Nullable
            ThingHandler baseHandler = childThing.getHandler();
            if (baseHandler != null && baseHandler instanceof BoschSHCHandler) {
                BoschSHCHandler handler = (BoschSHCHandler) baseHandler;
                @Nullable
                String deviceId = handler.getBoschID();

                handled = true;
                logger.debug("Registered device: {} - looking for {}", deviceId, updateDeviceId);

                if (deviceId != null && updateDeviceId.equals(deviceId)) {
                    logger.debug("Found child: {} - calling processUpdate (id: {}) with {}", handler,
                            deviceServiceData.id, state);
                    handler.processUpdate(deviceServiceData.id, state);
                }
            } else {
                logger.warn("longPoll: child handler for {} does not implement Bosch SHC handler", baseHandler);
            }
        }

        if (!handled) {
            logger.debug("Could not find a thing for device ID: {}", updateDeviceId);
        }
    }

    /**
     * Bridge callback handler for the failures during long polls.
     *
     * It will update the bridge status and try to access the SHC again.
     *
     * @param e error during long polling
     */
    private void handleLongPollFailure(Throwable e) {
        logger.warn("Long polling failed, will try to reconnect", e);
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                    "@text/offline.long-polling-failed.http-client-null");
            return;
        }

        this.updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.UNKNOWN.NONE,
                "@text/offline.long-polling-failed.trying-to-reconnect");
        scheduleInitialAccess(httpClient);
    }

    public Device getDeviceInfo(String deviceId)
            throws BoschSHCException, InterruptedException, TimeoutException, ExecutionException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            throw new BoschSHCException("HTTP client not initialized");
        }

        String url = httpClient.getBoschSmartHomeUrl(String.format("devices/%s", deviceId));
        Request request = httpClient.createRequest(url, GET);

        return httpClient.sendRequest(request, Device.class, Device::isValid, (Integer statusCode, String content) -> {
            JsonRestExceptionResponse errorResponse = gson.fromJson(content, JsonRestExceptionResponse.class);
            if (errorResponse != null && JsonRestExceptionResponse.isValid(errorResponse)) {
                if (errorResponse.errorCode.equals(JsonRestExceptionResponse.ENTITY_NOT_FOUND)) {
                    return new BoschSHCException("@text/offline.conf-error.invalid-device-id");
                } else {
                    return new BoschSHCException(
                            String.format("Request for info of device %s failed with status code %d and error code %s",
                                    deviceId, errorResponse.statusCode, errorResponse.errorCode));
                }
            } else {
                return new BoschSHCException(String.format("Request for info of device %s failed with status code %d",
                        deviceId, statusCode));
            }
        });
    }

    /**
     * Query the Bosch Smart Home Controller for the state of the given device.
     * <p>
     * The URL used for retrieving the state has the following structure:
     *
     * <pre>
     * https://{IP}:8444/smarthome/devices/{deviceId}/services/{serviceName}/state
     * </pre>
     *
     * @param deviceId Id of device to get state for
     * @param stateName Name of the state to query
     * @param stateClass Class to convert the resulting JSON to
     * @return the deserialized state object, may be <code>null</code>
     * @throws ExecutionException
     * @throws TimeoutException
     * @throws InterruptedException
     * @throws BoschSHCException
     */
    public <T extends BoschSHCServiceState> @Nullable T getState(String deviceId, String stateName, Class<T> stateClass)
            throws InterruptedException, TimeoutException, ExecutionException, BoschSHCException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            logger.warn("HttpClient not initialized");
            return null;
        }

        String url = httpClient.getServiceStateUrl(stateName, deviceId);
        logger.debug("getState(): Requesting \"{}\" from Bosch: {} via {}", stateName, deviceId, url);
        return getState(httpClient, url, stateClass);
    }

    /**
     * Queries the Bosch Smart Home Controller for the state using an explicit endpoint.
     *
     * @param <T> Type to which the resulting JSON should be deserialized to
     * @param endpoint The destination endpoint part of the URL
     * @param stateClass Class to convert the resulting JSON to
     * @return the deserialized state object, may be <code>null</code>
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws ExecutionException
     * @throws BoschSHCException
     */
    public <T extends BoschSHCServiceState> @Nullable T getState(String endpoint, Class<T> stateClass)
            throws InterruptedException, TimeoutException, ExecutionException, BoschSHCException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            logger.warn("HttpClient not initialized");
            return null;
        }

        String url = httpClient.getBoschSmartHomeUrl(endpoint);
        logger.debug("getState(): Requesting from Bosch: {}", url);
        return getState(httpClient, url, stateClass);
    }

    /**
     * Sends a HTTP GET request in order to retrieve a state from the Bosch Smart Home Controller.
     *
     * @param <T> Type to which the resulting JSON should be deserialized to
     * @param httpClient HTTP client used for sending the request
     * @param url URL at which the state should be retrieved
     * @param stateClass Class to convert the resulting JSON to
     * @return the deserialized state object, may be <code>null</code>
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws ExecutionException
     * @throws BoschSHCException
     */
    protected <T extends BoschSHCServiceState> @Nullable T getState(BoschHttpClient httpClient, String url,
            Class<T> stateClass) throws InterruptedException, TimeoutException, ExecutionException, BoschSHCException {
        Request request = httpClient.createRequest(url, GET).header("Accept", "application/json");

        ContentResponse contentResponse = request.send();

        String content = contentResponse.getContentAsString();
        logger.debug("getState(): Request complete: [{}] - return code: {}", content, contentResponse.getStatus());

        int statusCode = contentResponse.getStatus();
        if (statusCode != 200) {
            JsonRestExceptionResponse errorResponse = gson.fromJson(content, JsonRestExceptionResponse.class);
            if (errorResponse != null) {
                throw new BoschSHCException(
                        String.format("State request with URL %s failed with status code %d and error code %s", url,
                                errorResponse.statusCode, errorResponse.errorCode));
            } else {
                throw new BoschSHCException(
                        String.format("State request with URL %s failed with status code %d", url, statusCode));
            }
        }

        @Nullable
        T state = BoschSHCServiceState.fromJson(content, stateClass);
        if (state == null) {
            throw new BoschSHCException(String.format("Received invalid, expected type %s", stateClass.getName()));
        }
        return state;
    }

    /**
     * Sends a state change for a device to the controller
     *
     * @param deviceId Id of device to change state for
     * @param serviceName Name of service of device to change state for
     * @param state New state data to set for service
     *
     * @return Response of request
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public <T extends BoschSHCServiceState> @Nullable Response putState(String deviceId, String serviceName, T state)
            throws InterruptedException, TimeoutException, ExecutionException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            logger.warn("HttpClient not initialized");
            return null;
        }

        // Create request
        String url = httpClient.getServiceStateUrl(serviceName, deviceId);
        Request request = httpClient.createRequest(url, PUT, state);

        // Send request
        return request.send();
    }

    /**
     * Sends a HTTP POST request without a request body to the given endpoint.
     *
     * @param endpoint The destination endpoint part of the URL
     * @return the HTTP response
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public @Nullable Response postAction(String endpoint)
            throws InterruptedException, TimeoutException, ExecutionException {
        return postAction(endpoint, null);
    }

    /**
     * Sends a HTTP POST request with a request body to the given endpoint.
     *
     * @param <T> Type of the request
     * @param endpoint The destination endpoint part of the URL
     * @param requestBody object representing the request body to be sent, may be <code>null</code>
     * @return the HTTP response
     * @throws InterruptedException
     * @throws TimeoutException
     * @throws ExecutionException
     */
    public <T extends BoschSHCServiceState> @Nullable Response postAction(String endpoint, @Nullable T requestBody)
            throws InterruptedException, TimeoutException, ExecutionException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            logger.warn("HttpClient not initialized");
            return null;
        }

        String url = httpClient.getBoschSmartHomeUrl(endpoint);
        Request request = httpClient.createRequest(url, POST, requestBody);
        return request.send();
    }

    public @Nullable DeviceServiceData getServiceData(String deviceId, String serviceName)
            throws InterruptedException, TimeoutException, ExecutionException, BoschSHCException {
        @Nullable
        BoschHttpClient httpClient = this.httpClient;
        if (httpClient == null) {
            logger.warn("HttpClient not initialized");
            return null;
        }

        String url = httpClient.getServiceUrl(serviceName, deviceId);
        logger.debug("getState(): Requesting \"{}\" from Bosch: {} via {}", serviceName, deviceId, url);
        return getState(httpClient, url, DeviceServiceData.class);
    }
}
