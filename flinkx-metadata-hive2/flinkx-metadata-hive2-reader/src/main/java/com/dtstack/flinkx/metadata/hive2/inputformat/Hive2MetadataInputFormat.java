/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.metadata.hive2.inputformat;

import com.dtstack.flinkx.metadata.MetaDataCons;
import com.dtstack.flinkx.metadata.hive2.common.Hive2MetaDataCons;
import com.dtstack.flinkx.metadata.reader.inputformat.MetaDataInputFormat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * @author : tiezhu
 * @date : 2020/3/9
 */
public class Hive2MetadataInputFormat extends MetaDataInputFormat {
    protected List<String> tableColumn;
    protected List<String> partitionColumn;

    protected Map<String, Object> columnMap;

    @Override
    protected void beforeUnit(String currentQueryTable, String currentDbName) {
        getColumn(currentQueryTable, currentDbName);
        columnMap = transformDataToMap(executeSql(
                buildDescSql(currentDbName + "." + currentQueryTable, false)));
    }

    @Override
    public Map<String, Object> getTablePropertites(String currentQueryTable, String currentDbName) {
        ResultSet resultSet = executeSql(buildDescSql(currentDbName + "." + currentQueryTable, true));
        Map<String, Object> result;
        // 获取初始数据map
        result = transformDataToMap(resultSet);
        // 对初始数据清洗，除去无关信息，如带有key中#
        result.entrySet().removeIf(entry -> entry.getKey().contains("#"));
        // 过滤表字段的相关信息
        for (String item : tableColumn) {
            result.remove(item);
            result.remove(item + "_comment");
        }
        // 判断文件存储类型
        if (result.get(Hive2MetaDataCons.KEY_INPUT_FORMAT).toString().contains(Hive2MetaDataCons.INPUT_FORMAT_TEXT)) {
            result.put(MetaDataCons.KEY_STORED_TYPE, Hive2MetaDataCons.TYPE_TEXT);
        }
        if (result.get(Hive2MetaDataCons.KEY_INPUT_FORMAT).toString().contains(Hive2MetaDataCons.INPUT_FORMAT_ORC)) {
            result.put(MetaDataCons.KEY_STORED_TYPE, Hive2MetaDataCons.TYPE_ORC);
        }
        if (result.get(Hive2MetaDataCons.KEY_INPUT_FORMAT).toString().contains(Hive2MetaDataCons.INPUT_FORMAT_PARQUET)) {
            result.put(MetaDataCons.KEY_STORED_TYPE, Hive2MetaDataCons.TYPE_PARQUET);
        }
        return result;
    }

    @Override
    public Map<String, Object> getColumnPropertites(String currentQueryTable) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> tempColumnList = new ArrayList<>();

        for (String item : tableColumn) {
            tempColumnList.add(setColumnMap(item, columnMap, tableColumn.indexOf(item)));
        }
        result.put(MetaDataCons.KEY_COLUMN, tempColumnList);
        return result;
    }

    @Override
    public Map<String, Object> getPartitionPropertites(String currentQueryTable, String currentDbName) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> tempPartitionColumnList = new ArrayList<>();

            for (String item : partitionColumn) {
                tempPartitionColumnList.add(setColumnMap(item, columnMap, partitionColumn.indexOf(item)));
            }
            result.put(Hive2MetaDataCons.KEY_PARTITION_COLUMN, tempPartitionColumnList);
            if (!tempPartitionColumnList.isEmpty()) {
                result.put("partitions", getPartitions(currentDbName + "." + currentQueryTable));
            }
        } catch (Exception e) {
            setErrorMessage(e, "get partitions error");
        }
        return result;
    }

    @Override
    public String queryTableSql() {
        return "show tables";
    }

    @Override
    public String queryDBSql() {
        return "show databases";
    }

    @Override
    public String changeDBSql(String dbName) {
        return "use " + dbName;
    }

    /**
     * 构建查询语句
     */
    public String buildDescSql(String currentQueryTable, boolean formatted) {
        String sql;
        if (formatted) {
            sql = "DESC FORMATTED " + currentQueryTable;
        } else {
            sql = "DESC " + currentQueryTable;
        }
        return sql;
    }

    /**
     * 获取表中字段名称，包括分区字段和非分区字段
     */
    public void getColumn(String currentQueryTable, String currentDbName) {
        try {
            boolean isPartitionColumn = false;
            tableColumn = new ArrayList<>();
            partitionColumn = new ArrayList<>();
            ResultSet temp = executeSql(buildDescSql(currentDbName + "." + currentQueryTable, false));
            while (temp.next()) {
                if (temp.getString(1).trim().contains("Partition Information")) {
                    isPartitionColumn = true;
                }
                if (isPartitionColumn) {
                    partitionColumn.add(temp.getString(1).trim());
                } else {
                    tableColumn.add(temp.getString(1));
                }
            }
            // 除去多余的字段
            tableColumn.removeIf(item -> item.contains("#") || item.isEmpty());
            partitionColumn.removeIf(item -> item.contains("#") || item.isEmpty());

        } catch (Exception e) {
            setErrorMessage(e, "get column error!");
        }
    }

    /**
     * 从查询结果中构建Map
     */
    public Map<String, Object> transformDataToMap(ResultSet resultSet) {
        Map<String, Object> result = new HashMap<>();
        try {
            while (resultSet.next()) {
                String key1 = resultSet.getString(1);
                String key2 = resultSet.getString(2);
                String key3 = resultSet.getString(3);
                if (key1.isEmpty()) {
                    if (key2 != null) {
                        result.put(key2.trim(), key3.trim());
                    }
                } else {
                    if (key2 != null) {
                        result.put(toLowerCaseFirstOne(key1).replace(":", "").trim(), key2.trim());
                    }
                    if (key3 != null) {
                        result.put(key1.trim() + "_comment", key3);
                    }
                }
            }
        } catch (Exception e) {
            setErrorMessage(e, "transform data error");
        }
        return result;
    }

    /**
     * 通过查询得到的结果构建字段名相应的信息
     *
     * @return result 返回column信息List<Map>
     */
    public Map<String, Object> setColumnMap(String columnName, Map<String, Object> map, int index) {
        Map<String, Object> result = new HashMap<>();
        result.put(MetaDataCons.KEY_COLUMN_NAME, columnName);
        result.put(MetaDataCons.KEY_COLUMN_COMMENT, map.get(columnName + "_comment"));
        result.put(MetaDataCons.KEY_COLUMN_TYPE, map.get(columnName));
        result.put(MetaDataCons.KEY_COLUMN_INDEX, index);
        return result;
    }

    /**
     * 获取分区partitions
     */
    public List<String> getPartitions(String currentTable) throws SQLException {
        List<String> partitions = new ArrayList<>();
        ResultSet resultSet = executeSql("show partitions " + currentTable);
        while (resultSet.next()) {
            partitions.add(resultSet.getString(1));
        }
        return partitions;
    }

    /**
     * 将字符串首字母转小写
     */
    public String toLowerCaseFirstOne(String s) {
        if (Character.isLowerCase(s.charAt(0))) {
            return s;
        } else {
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
    }
}
