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

import static java.util.Map.entry;
import static org.openhab.binding.miele.internal.MieleBindingConstants.*;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

import org.openhab.binding.miele.internal.DeviceMetaData;
import org.openhab.binding.miele.internal.DeviceUtil;
import org.openhab.binding.miele.internal.MieleTranslationProvider;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.Type;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ApplianceChannelSelector} for dishwashers
 *
 * @author Karel Goderis - Initial contribution
 * @author Kai Kreuzer - Changed START_TIME to DateTimeType
 * @author Jacob Laursen - Added power/water consumption channels, raw channels
 */
public enum DishwasherChannelSelector implements ApplianceChannelSelector {

    PRODUCT_TYPE("productTypeId", "productType", StringType.class, true, false),
    DEVICE_TYPE("mieleDeviceType", "deviceType", StringType.class, true, false),
    STATE_TEXT(STATE_PROPERTY_NAME, STATE_TEXT_CHANNEL_ID, StringType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            State state = DeviceUtil.getStateTextState(s, dmd, translationProvider);
            if (state != null) {
                return state;
            }
            return super.getState(s, dmd, translationProvider);
        }
    },
    STATE(null, STATE_CHANNEL_ID, DecimalType.class, false, false),
    PROGRAM_TEXT(PROGRAM_ID_PROPERTY_NAME, PROGRAM_TEXT_CHANNEL_ID, StringType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            State state = DeviceUtil.getTextState(s, dmd, translationProvider, programs, MISSING_PROGRAM_TEXT_PREFIX,
                    MIELE_DISHWASHER_TEXT_PREFIX);
            if (state != null) {
                return state;
            }
            return super.getState(s, dmd, translationProvider);
        }
    },
    PROGRAM(null, PROGRAM_CHANNEL_ID, DecimalType.class, false, false),
    PROGRAM_PHASE_TEXT(PHASE_PROPERTY_NAME, PHASE_TEXT_CHANNEL_ID, StringType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            State state = DeviceUtil.getTextState(s, dmd, translationProvider, phases, MISSING_PHASE_TEXT_PREFIX,
                    MIELE_DISHWASHER_TEXT_PREFIX);
            if (state != null) {
                return state;
            }
            return super.getState(s, dmd, translationProvider);
        }
    },
    PROGRAM_PHASE(RAW_PHASE_PROPERTY_NAME, PHASE_CHANNEL_ID, DecimalType.class, false, false),
    START_TIME("startTime", "start", DateTimeType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            Date date = new Date();
            SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+0"));
            try {
                date.setTime(Long.valueOf(s) * 60000);
            } catch (Exception e) {
                date.setTime(0);
            }
            return getState(dateFormatter.format(date));
        }
    },
    DURATION("duration", "duration", DateTimeType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            Date date = new Date();
            SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+0"));
            try {
                date.setTime(Long.valueOf(s) * 60000);
            } catch (Exception e) {
                date.setTime(0);
            }
            return getState(dateFormatter.format(date));
        }
    },
    ELAPSED_TIME("elapsedTime", "elapsed", DateTimeType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            Date date = new Date();
            SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+0"));
            try {
                date.setTime(Long.valueOf(s) * 60000);
            } catch (Exception e) {
                date.setTime(0);
            }
            return getState(dateFormatter.format(date));
        }
    },
    FINISH_TIME("finishTime", "finish", DateTimeType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            Date date = new Date();
            SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+0"));
            try {
                date.setTime(Long.valueOf(s) * 60000);
            } catch (Exception e) {
                date.setTime(0);
            }
            return getState(dateFormatter.format(date));
        }
    },
    DOOR("signalDoor", "door", OpenClosedType.class, false, false) {
        @Override
        public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
            if ("true".equals(s)) {
                return getState("OPEN");
            }

            if ("false".equals(s)) {
                return getState("CLOSED");
            }

            return UnDefType.UNDEF;
        }
    },
    SWITCH(null, "switch", OnOffType.class, false, false),
    POWER_CONSUMPTION(EXTENDED_DEVICE_STATE_PROPERTY_NAME, POWER_CONSUMPTION_CHANNEL_ID, QuantityType.class, false,
            true),
    WATER_CONSUMPTION(EXTENDED_DEVICE_STATE_PROPERTY_NAME, WATER_CONSUMPTION_CHANNEL_ID, QuantityType.class, false,
            true);

    private final Logger logger = LoggerFactory.getLogger(DishwasherChannelSelector.class);

    private static final Map<String, String> programs = Map.ofEntries(entry("26", "pots-and-pans"),
            entry("27", "clean-machine"), entry("28", "economy"), entry("30", "normal"), entry("32", "sensor-wash"),
            entry("34", "energy-saver"), entry("35", "china-and-crystal"), entry("36", "extra-quiet"),
            entry("37", "saniwash"), entry("38", "quickpowerwash"), entry("42", "tall-items"));

    private static final Map<String, String> phases = Map.ofEntries(entry("2", "pre-Wash"), entry("3", "main-wash"),
            entry("4", "rinses"), entry("6", "final-rinse"), entry("7", "drying"), entry("8", "finished"));

    private final String mieleID;
    private final String channelID;
    private final Class<? extends Type> typeClass;
    private final boolean isProperty;
    private final boolean isExtendedState;

    DishwasherChannelSelector(String propertyID, String channelID, Class<? extends Type> typeClass, boolean isProperty,
            boolean isExtendedState) {
        this.mieleID = propertyID;
        this.channelID = channelID;
        this.typeClass = typeClass;
        this.isProperty = isProperty;
        this.isExtendedState = isExtendedState;
    }

    @Override
    public String toString() {
        return mieleID;
    }

    @Override
    public String getMieleID() {
        return mieleID;
    }

    @Override
    public String getChannelID() {
        return channelID;
    }

    @Override
    public boolean isProperty() {
        return isProperty;
    }

    @Override
    public boolean isExtendedState() {
        return isExtendedState;
    }

    @Override
    public State getState(String s, DeviceMetaData dmd, MieleTranslationProvider translationProvider) {
        return this.getState(s, dmd);
    }

    @Override
    public State getState(String s, DeviceMetaData dmd) {
        if (dmd != null) {
            String localizedValue = dmd.getMieleEnum(s);
            if (localizedValue == null) {
                localizedValue = dmd.LocalizedValue;
            }
            if (localizedValue == null) {
                localizedValue = s;
            }

            return getState(localizedValue);
        } else {
            return getState(s);
        }
    }

    public State getState(String s) {
        try {
            Method valueOf = typeClass.getMethod("valueOf", String.class);
            State state = (State) valueOf.invoke(typeClass, s);
            if (state != null) {
                return state;
            }
        } catch (Exception e) {
            logger.error("An exception occurred while converting '{}' into a State", s);
        }

        return null;
    }
}
