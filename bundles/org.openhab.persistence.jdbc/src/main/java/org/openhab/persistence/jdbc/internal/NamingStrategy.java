/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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
package org.openhab.persistence.jdbc.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.persistence.jdbc.dto.ItemVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages strategy for table names.
 *
 * @author Jacob Laursen - Initial contribution
 */
@NonNullByDefault
public class NamingStrategy {

    private static final String ITEM_NAME_PATTERN = "[^a-zA-Z_0-9\\-]";

    private final Logger logger = LoggerFactory.getLogger(NamingStrategy.class);

    private JdbcConfiguration configuration;

    public NamingStrategy(JdbcConfiguration configuration) {
        this.configuration = configuration;
    }

    public String getTableName(int rowId, String itemName) {
        if (configuration.getTableUseRealItemNames()) {
            return formatTableName(itemName);
        } else {
            int digits = configuration.getTableIdDigitCount();
            if (digits > 0) {
                return configuration.getTableNamePrefix()
                        + String.format("%0" + configuration.getTableIdDigitCount() + "d", rowId);
            } else {
                return configuration.getTableNamePrefix() + rowId;
            }
        }
    }

    private String formatTableName(String itemName) {
        return (itemName.replaceAll(ITEM_NAME_PATTERN, "")).toLowerCase();
    }

    public List<ItemVO> prepareMigration(List<String> itemTables, Map<Integer, String> itemIdToItemNameMap,
            String itemsManageTable) {
        List<ItemVO> oldNewTablenames = new ArrayList<>();
        Map<String, Integer> tableNameToItemIdMap = new HashMap<>();
        String tableNamePrefix = configuration.getTableNamePrefix();
        boolean tableUseRealItemNames = configuration.getTableUseRealItemNames();
        int tableNamePrefixLength = tableNamePrefix.length();

        for (String oldName : itemTables) {
            int id = -1;
            logger.info("JDBC::formatTableNames: found Table Name= {}", oldName);

            if (oldName.startsWith(tableNamePrefix) && !oldName.contains("_")) {
                id = Integer.parseInt(oldName.substring(tableNamePrefixLength));
                logger.info("JDBC::formatTableNames: found Table with Prefix '{}' Name= {} id= {}", tableNamePrefix,
                        oldName, (id));
            } else if (oldName.contains("_")) {
                try {
                    id = Integer.parseInt(oldName.substring(oldName.lastIndexOf("_") + 1));
                    logger.info("JDBC::formatTableNames: found Table Name= {} id= {}", oldName, (id));
                } catch (NumberFormatException e) {
                    // Fall through.
                }
            }

            if (id == -1 && !tableUseRealItemNames) {
                if (tableNameToItemIdMap.isEmpty()) {
                    for (Entry<Integer, String> entry : itemIdToItemNameMap.entrySet()) {
                        String itemName = entry.getValue();
                        tableNameToItemIdMap.put(formatTableName(itemName), entry.getKey());
                    }
                }
                Integer itemId = tableNameToItemIdMap.get(oldName);
                if (itemId != null) {
                    id = itemId;
                }
                logger.info("JDBC::formatTableNames: found Table Name= {} id= {}", oldName, (id));
            }
            logger.info("JDBC::formatTableNames: found Table id= {}", id);

            String itemName = itemIdToItemNameMap.get(id);

            if (!Objects.isNull(itemName)) {
                String newName = getTableName(id, itemName);
                if (newName.equalsIgnoreCase(itemsManageTable)) {
                    logger.error(
                            "JDBC::formatTableNames: Table '{}' could NOT be renamed to '{}' since it conflicts with manage table",
                            oldName, newName);
                } else if (!oldName.equalsIgnoreCase(newName)) {
                    oldNewTablenames.add(new ItemVO(oldName, newName));
                    logger.info("JDBC::formatTableNames: Table '{}' will be renamed to '{}'", oldName, newName);
                } else {
                    logger.info("JDBC::formatTableNames: Table oldName='{}' newName='{}' nothing to rename", oldName,
                            newName);
                }
            } else {
                logger.error("JDBC::formatTableNames: Table '{}' could NOT be renamed for item '{}'", oldName,
                        itemName);
                break;
            }
        }

        return oldNewTablenames;
    }
}