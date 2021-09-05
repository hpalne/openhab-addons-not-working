/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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
package org.openhab.binding.miele.internal.handler;

import static org.openhab.binding.miele.internal.MieleBindingConstants.*;

import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

/**
 * The {@link CoffeeMachineHandler} is responsible for handling commands,
 * which are sent to one of the channels
 *
 * @author Stephan Esch - Initial contribution
 * @author Martin Lepsy - fixed handling of empty JSON results
 */
public class CoffeeMachineHandler extends MieleApplianceHandler<CoffeeMachineChannelSelector> {

    private final Logger logger = LoggerFactory.getLogger(CoffeeMachineHandler.class);

    public CoffeeMachineHandler(Thing thing) {
        super(thing, CoffeeMachineChannelSelector.class, "CoffeeSystem");
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        super.handleCommand(channelUID, command);

        String channelID = channelUID.getId();
        String uid = (String) getThing().getConfiguration().getProperties().get(APPLIANCE_ID);
        String protocol = (String) getThing().getConfiguration().getProperties().get(PROTOCOL_NAME);
        protocol = protocol == PROTOCOL_WIFI ? PROTOCOL_LAN : protocol;

        CoffeeMachineChannelSelector selector = (CoffeeMachineChannelSelector) getValueSelectorFromChannelID(channelID);
        JsonElement result = null;

        try {
            if (selector != null) {
                switch (selector) {
                    case SWITCH: {
                        if (command.equals(OnOffType.ON)) {
                            result = bridgeHandler.invokeOperation(protocol, uid, modelID, "switchOn");
                        } else if (command.equals(OnOffType.OFF)) {
                            result = bridgeHandler.invokeOperation(protocol, uid, modelID, "switchOff");
                        }
                        break;
                    }
                    default: {
                        if (!(command instanceof RefreshType)) {
                            logger.debug("{} is a read-only channel that does not accept commands",
                                    selector.getChannelID());
                        }
                    }
                }
            }
            // process result
            if (isResultProcessable(result)) {
                logger.debug("Result of operation is {}", result.getAsString());
            }
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "An error occurred while trying to set the read-only variable associated with channel '{}' to '{}'",
                    channelID, command.toString());
        }
    }
}
