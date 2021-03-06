/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.metastore.CachingHiveMetastore;
import com.facebook.presto.hive.metastore.HiveMetastore;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorColumnHandle;
import com.facebook.presto.spi.ConnectorMetadata;
import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorPartition;
import com.facebook.presto.spi.ConnectorPartitionResult;
import com.facebook.presto.spi.ConnectorRecordSetProvider;
import com.facebook.presto.spi.ConnectorRecordSinkProvider;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.ConnectorSplitManager;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Domain;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.RecordSet;
import com.facebook.presto.spi.RecordSink;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.TupleDomain;
import com.facebook.presto.spi.ViewNotFoundException;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.net.HostAndPort;
import io.airlift.units.Duration;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static com.facebook.presto.hive.HiveBucketing.HiveBucket;
import static com.facebook.presto.hive.HiveUtil.partitionIdGetter;
import static com.facebook.presto.hive.util.Types.checkType;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.TimeZoneKey.UTC_KEY;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Maps.uniqueIndex;
import static com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.testing.Assertions.assertInstanceOf;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(groups = "hive")
public abstract class AbstractTestHiveClient
{
    private static final ConnectorSession SESSION = new ConnectorSession("user", "test", "default", "default", UTC_KEY, Locale.ENGLISH, null, null);

    protected static final String INVALID_DATABASE = "totally_invalid_database_name";
    protected static final String INVALID_TABLE = "totally_invalid_table_name";
    protected static final String INVALID_COLUMN = "totally_invalid_column_name";

    protected String database;
    protected SchemaTableName tablePartitionFormat;
    protected SchemaTableName tableUnpartitioned;
    protected SchemaTableName tableOffline;
    protected SchemaTableName tableOfflinePartition;
    protected SchemaTableName view;
    protected SchemaTableName invalidTable;
    protected SchemaTableName tableBucketedStringInt;
    protected SchemaTableName tableBucketedBigintBoolean;
    protected SchemaTableName tableBucketedDoubleFloat;

    protected SchemaTableName temporaryCreateTable;
    protected SchemaTableName temporaryCreateSampledTable;
    protected SchemaTableName temporaryRenameTableOld;
    protected SchemaTableName temporaryRenameTableNew;
    protected SchemaTableName temporaryCreateView;
    protected String tableOwner;

    protected ConnectorTableHandle invalidTableHandle;

    protected ConnectorColumnHandle dsColumn;
    protected ConnectorColumnHandle fileFormatColumn;
    protected ConnectorColumnHandle dummyColumn;
    protected ConnectorColumnHandle intColumn;
    protected ConnectorColumnHandle invalidColumnHandle;

    protected Set<ConnectorPartition> partitions;
    protected Set<ConnectorPartition> unpartitionedPartitions;
    protected ConnectorPartition invalidPartition;

    protected DateTimeZone timeZone;

    protected HiveMetastore metastoreClient;

    protected ConnectorMetadata metadata;
    protected ConnectorSplitManager splitManager;
    protected ConnectorRecordSetProvider recordSetProvider;
    protected ConnectorRecordSinkProvider recordSinkProvider;

    protected void setupHive(String connectorId, String databaseName, String timeZoneId)
    {
        database = databaseName;
        tablePartitionFormat = new SchemaTableName(database, "presto_test_partition_format");
        tableUnpartitioned = new SchemaTableName(database, "presto_test_unpartitioned");
        tableOffline = new SchemaTableName(database, "presto_test_offline");
        tableOfflinePartition = new SchemaTableName(database, "presto_test_offline_partition");
        view = new SchemaTableName(database, "presto_test_view");
        invalidTable = new SchemaTableName(database, INVALID_TABLE);
        tableBucketedStringInt = new SchemaTableName(database, "presto_test_bucketed_by_string_int");
        tableBucketedBigintBoolean = new SchemaTableName(database, "presto_test_bucketed_by_bigint_boolean");
        tableBucketedDoubleFloat = new SchemaTableName(database, "presto_test_bucketed_by_double_float");

        temporaryCreateTable = new SchemaTableName(database, "tmp_presto_test_create_" + randomName());
        temporaryCreateSampledTable = new SchemaTableName(database, "tmp_presto_test_create_" + randomName());
        temporaryRenameTableOld = new SchemaTableName(database, "tmp_presto_test_rename_" + randomName());
        temporaryRenameTableNew = new SchemaTableName(database, "tmp_presto_test_rename_" + randomName());
        temporaryCreateView = new SchemaTableName(database, "tmp_presto_test_create_" + randomName());
        tableOwner = "presto_test";

        invalidTableHandle = new HiveTableHandle("hive", database, INVALID_TABLE, SESSION);

        dsColumn = new HiveColumnHandle(connectorId, "ds", 0, HiveType.STRING, -1, true);
        fileFormatColumn = new HiveColumnHandle(connectorId, "file_format", 1, HiveType.STRING, -1, true);
        dummyColumn = new HiveColumnHandle(connectorId, "dummy", 2, HiveType.INT, -1, true);
        intColumn = new HiveColumnHandle(connectorId, "t_int", 0, HiveType.INT, -1, true);
        invalidColumnHandle = new HiveColumnHandle(connectorId, INVALID_COLUMN, 0, HiveType.STRING, 0, false);

        partitions = ImmutableSet.<ConnectorPartition>builder()
                .add(new HivePartition(tablePartitionFormat,
                        "ds=2012-12-29/file_format=textfile/dummy=1",
                        ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                                .put(dsColumn, utf8Slice("2012-12-29"))
                                .put(fileFormatColumn, utf8Slice("textfile"))
                                .put(dummyColumn, 1L)
                                .build(),
                        Optional.<HiveBucket>absent()))
                .add(new HivePartition(tablePartitionFormat,
                        "ds=2012-12-29/file_format=sequencefile/dummy=2",
                        ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                                .put(dsColumn, utf8Slice("2012-12-29"))
                                .put(fileFormatColumn, utf8Slice("sequencefile"))
                                .put(dummyColumn, 2L)
                                .build(),
                        Optional.<HiveBucket>absent()))
                .add(new HivePartition(tablePartitionFormat,
                        "ds=2012-12-29/file_format=rcfile-text/dummy=3",
                        ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                                .put(dsColumn, utf8Slice("2012-12-29"))
                                .put(fileFormatColumn, utf8Slice("rcfile-text"))
                                .put(dummyColumn, 3L)
                                .build(),
                        Optional.<HiveBucket>absent()))
                .add(new HivePartition(tablePartitionFormat,
                        "ds=2012-12-29/file_format=rcfile-binary/dummy=4",
                        ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                                .put(dsColumn, utf8Slice("2012-12-29"))
                                .put(fileFormatColumn, utf8Slice("rcfile-binary"))
                                .put(dummyColumn, 4L)
                                .build(),
                        Optional.<HiveBucket>absent()))
                .build();
        unpartitionedPartitions = ImmutableSet.<ConnectorPartition>of(new HivePartition(tableUnpartitioned));
        invalidPartition = new HivePartition(invalidTable, "unknown", ImmutableMap.<ConnectorColumnHandle, Comparable<?>>of(), Optional.<HiveBucket>absent());
        timeZone = DateTimeZone.forTimeZone(TimeZone.getTimeZone(timeZoneId));
    }

    protected void setup(String host, int port, String databaseName, String timeZone)
    {
        setup(host, port, databaseName, timeZone, "hive-test", 100, 50);
    }

    protected void setup(String host, int port, String databaseName, String timeZoneId, String connectorName, int maxOutstandingSplits, int maxThreads)
    {
        setupHive(connectorName, databaseName, timeZoneId);

        HiveClientConfig hiveClientConfig = new HiveClientConfig();
        String proxy = System.getProperty("hive.metastore.thrift.client.socks-proxy");
        if (proxy != null) {
            hiveClientConfig.setMetastoreSocksProxy(HostAndPort.fromString(proxy));
        }

        HiveCluster hiveCluster = new TestingHiveCluster(hiveClientConfig, host, port);
        ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("hive-%s"));

        metastoreClient = new CachingHiveMetastore(hiveCluster, executor, Duration.valueOf("1m"), Duration.valueOf("15s"));

        HiveClient client = new HiveClient(
                new HiveConnectorId(connectorName),
                metastoreClient,
                new NamenodeStats(),
                new HdfsEnvironment(new HdfsConfiguration(hiveClientConfig)),
                new HadoopDirectoryLister(),
                timeZone,
                sameThreadExecutor(),
                hiveClientConfig.getMaxSplitSize(),
                maxOutstandingSplits,
                maxThreads,
                hiveClientConfig.getMinPartitionBatchSize(),
                hiveClientConfig.getMaxPartitionBatchSize(),
                hiveClientConfig.getMaxInitialSplitSize(),
                hiveClientConfig.getMaxInitialSplits(),
                false,
                true,
                hiveClientConfig.getHiveStorageFormat(),
                false);

        metadata = client;
        splitManager = client;
        recordSetProvider = client;
        recordSinkProvider = client;
    }

    @Test
    public void testGetDatabaseNames()
            throws Exception
    {
        List<String> databases = metadata.listSchemaNames(SESSION);
        assertTrue(databases.contains(database));
    }

    @Test
    public void testGetTableNames()
            throws Exception
    {
        List<SchemaTableName> tables = metadata.listTables(SESSION, database);
        assertTrue(tables.contains(tablePartitionFormat));
    }

    @Test
    public void testListUnknownSchema()
    {
        assertNull(metadata.getTableHandle(SESSION, new SchemaTableName(INVALID_DATABASE, INVALID_TABLE)));
        assertEquals(metadata.listTables(SESSION, INVALID_DATABASE), ImmutableList.of());
        assertEquals(metadata.listTableColumns(SESSION, new SchemaTablePrefix(INVALID_DATABASE, INVALID_TABLE)), ImmutableMap.of());
        assertEquals(metadata.listViews(SESSION, INVALID_DATABASE), ImmutableList.of());
        assertEquals(metadata.getViews(SESSION, new SchemaTablePrefix(INVALID_DATABASE, INVALID_TABLE)), ImmutableMap.of());
    }

    @Test
    public void testGetPartitions()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tablePartitionFormat);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        assertExpectedPartitions(partitionResult.getPartitions(), partitions);
    }

    @Test
    public void testGetPartitionsWithBindings()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tablePartitionFormat);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.withColumnDomains(ImmutableMap.of(intColumn, Domain.singleValue(5L))));
        assertExpectedPartitions(partitionResult.getPartitions(), partitions);
    }

    @Test(expectedExceptions = TableNotFoundException.class)
    public void testGetPartitionsException()
            throws Exception
    {
        splitManager.getPartitions(invalidTableHandle, TupleDomain.<ConnectorColumnHandle>all());
    }

    @Test
    public void testGetPartitionNames()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tablePartitionFormat);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        assertExpectedPartitions(partitionResult.getPartitions(), partitions);
    }

    protected void assertExpectedPartitions(List<ConnectorPartition> actualPartitions, Iterable<ConnectorPartition> expectedPartitions)
    {
        Map<String, ConnectorPartition> actualById = uniqueIndex(actualPartitions, partitionIdGetter());
        for (ConnectorPartition expected : expectedPartitions) {
            assertInstanceOf(expected, HivePartition.class);
            HivePartition expectedPartition = (HivePartition) expected;

            ConnectorPartition actual = actualById.get(expectedPartition.getPartitionId());
            assertEquals(actual, expected);
            assertInstanceOf(actual, HivePartition.class);
            HivePartition actualPartition = (HivePartition) actual;

            assertNotNull(actualPartition, "partition " + expectedPartition.getPartitionId());
            assertEquals(actualPartition.getPartitionId(), expectedPartition.getPartitionId());
            assertEquals(actualPartition.getKeys(), expectedPartition.getKeys());
            assertEquals(actualPartition.getTableName(), expectedPartition.getTableName());
            assertEquals(actualPartition.getBucket(), expectedPartition.getBucket());
            assertEquals(actualPartition.getTupleDomain(), expectedPartition.getTupleDomain());
        }
    }

    @Test
    public void testGetPartitionNamesUnpartitioned()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableUnpartitioned);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        assertEquals(partitionResult.getPartitions().size(), 1);
        assertEquals(partitionResult.getPartitions(), unpartitionedPartitions);
    }

    @Test(expectedExceptions = TableNotFoundException.class)
    public void testGetPartitionNamesException()
            throws Exception
    {
        splitManager.getPartitions(invalidTableHandle, TupleDomain.<ConnectorColumnHandle>all());
    }

    @SuppressWarnings({"ValueOfIncrementOrDecrementUsed", "UnusedAssignment"})
    @Test
    public void testGetTableSchemaPartitionFormat()
            throws Exception
    {
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(getTableHandle(tablePartitionFormat));
        Map<String, ColumnMetadata> map = uniqueIndex(tableMetadata.getColumns(), columnNameGetter());

        int i = 0;
        assertPrimitiveField(map, i++, "t_string", VARCHAR, false);
        assertPrimitiveField(map, i++, "t_tinyint", BIGINT, false);
        assertPrimitiveField(map, i++, "t_smallint", BIGINT, false);
        assertPrimitiveField(map, i++, "t_int", BIGINT, false);
        assertPrimitiveField(map, i++, "t_bigint", BIGINT, false);
        assertPrimitiveField(map, i++, "t_float", DOUBLE, false);
        assertPrimitiveField(map, i++, "t_double", DOUBLE, false);
        assertPrimitiveField(map, i++, "t_boolean", BOOLEAN, false);
        assertPrimitiveField(map, i++, "ds", VARCHAR, true);
        assertPrimitiveField(map, i++, "file_format", VARCHAR, true);
        assertPrimitiveField(map, i++, "dummy", BIGINT, true);
    }

    @Test
    public void testGetTableSchemaUnpartitioned()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableUnpartitioned);
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(tableHandle);
        Map<String, ColumnMetadata> map = uniqueIndex(tableMetadata.getColumns(), columnNameGetter());

        assertPrimitiveField(map, 0, "t_string", VARCHAR, false);
        assertPrimitiveField(map, 1, "t_tinyint", BIGINT, false);
    }

    @Test
    public void testGetTableSchemaOffline()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableOffline);
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(tableHandle);
        Map<String, ColumnMetadata> map = uniqueIndex(tableMetadata.getColumns(), columnNameGetter());

        assertPrimitiveField(map, 0, "t_string", VARCHAR, false);
    }

    @Test
    public void testGetTableSchemaOfflinePartition()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableOfflinePartition);
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(tableHandle);
        Map<String, ColumnMetadata> map = uniqueIndex(tableMetadata.getColumns(), columnNameGetter());

        assertPrimitiveField(map, 0, "t_string", VARCHAR, false);
    }

    @Test
    public void testGetTableSchemaException()
            throws Exception
    {
        assertNull(metadata.getTableHandle(SESSION, invalidTable));
    }

    @Test
    public void testGetPartitionSplitsBatch()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tablePartitionFormat);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        ConnectorSplitSource splitSource = splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions());

        assertEquals(getSplitCount(splitSource), partitions.size());
    }

    @Test
    public void testGetPartitionSplitsBatchUnpartitioned()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableUnpartitioned);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        ConnectorSplitSource splitSource = splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions());

        assertEquals(getSplitCount(splitSource), 1);
    }

    @Test(expectedExceptions = TableNotFoundException.class)
    public void testGetPartitionSplitsBatchInvalidTable()
            throws Exception
    {
        splitManager.getPartitionSplits(invalidTableHandle, ImmutableList.of(invalidPartition));
    }

    @Test
    public void testGetPartitionSplitsEmpty()
            throws Exception
    {
        ConnectorSplitSource splitSource = splitManager.getPartitionSplits(invalidTableHandle, ImmutableList.<ConnectorPartition>of());
        // fetch full list
        getSplitCount(splitSource);
    }

    @Test
    public void testGetPartitionTableOffline()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableOffline);
        try {
            splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
            fail("expected TableOfflineException");
        }
        catch (TableOfflineException e) {
            assertEquals(e.getTableName(), tableOffline);
        }
    }

    @Test
    public void testGetPartitionSplitsTableOfflinePartition()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableOfflinePartition);
        assertNotNull(tableHandle);

        ConnectorColumnHandle dsColumn = metadata.getColumnHandles(tableHandle).get("ds");
        assertNotNull(dsColumn);

        Domain domain = Domain.singleValue(utf8Slice("2012-12-30"));
        TupleDomain<ConnectorColumnHandle> tupleDomain = TupleDomain.withColumnDomains(ImmutableMap.of(dsColumn, domain));
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, tupleDomain);
        for (ConnectorPartition partition : partitionResult.getPartitions()) {
            if (domain.equals(partition.getTupleDomain().getDomains().get(dsColumn))) {
                try {
                    getSplitCount(splitManager.getPartitionSplits(tableHandle, ImmutableList.of(partition)));
                    fail("Expected PartitionOfflineException");
                }
                catch (PartitionOfflineException e) {
                    assertEquals(e.getTableName(), tableOfflinePartition);
                    assertEquals(e.getPartition(), "ds=2012-12-30");
                }
            }
            else {
                getSplitCount(splitManager.getPartitionSplits(tableHandle, ImmutableList.of(partition)));
            }
        }
    }

    @Test
    public void testBucketedTableStringInt()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableBucketedStringInt);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        assertTableIsBucketed(tableHandle);

        String testString = "test";
        Long testInt = 13L;
        Long testSmallint = 12L;

        // Reverse the order of bindings as compared to bucketing order
        ImmutableMap<ConnectorColumnHandle, Comparable<?>> bindings = ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                .put(columnHandles.get(columnIndex.get("t_int")), testInt)
                .put(columnHandles.get(columnIndex.get("t_string")), utf8Slice(testString))
                .put(columnHandles.get(columnIndex.get("t_smallint")), testSmallint)
                .build();

        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.withFixedValues(bindings));
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), 1);

        boolean rowFound = false;
        try (RecordCursor cursor = recordSetProvider.getRecordSet(splits.get(0), columnHandles).cursor()) {
            while (cursor.advanceNextPosition()) {
                if (testString.equals(cursor.getSlice(columnIndex.get("t_string")).toStringUtf8()) &&
                        testInt == cursor.getLong(columnIndex.get("t_int")) &&
                        testSmallint == cursor.getLong(columnIndex.get("t_smallint"))) {
                    rowFound = true;
                }
            }
            assertTrue(rowFound);
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testBucketedTableBigintBoolean()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableBucketedBigintBoolean);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        assertTableIsBucketed(tableHandle);

        String testString = "test";
        Long testBigint = 89L;
        Boolean testBoolean = true;

        ImmutableMap<ConnectorColumnHandle, Comparable<?>> bindings = ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                .put(columnHandles.get(columnIndex.get("t_string")), utf8Slice(testString))
                .put(columnHandles.get(columnIndex.get("t_bigint")), testBigint)
                .put(columnHandles.get(columnIndex.get("t_boolean")), testBoolean)
                .build();

        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.withFixedValues(bindings));
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), 1);

        boolean rowFound = false;
        try (RecordCursor cursor = recordSetProvider.getRecordSet(splits.get(0), columnHandles).cursor()) {
            while (cursor.advanceNextPosition()) {
                if (testString.equals(cursor.getSlice(columnIndex.get("t_string")).toStringUtf8()) &&
                        testBigint == cursor.getLong(columnIndex.get("t_bigint")) &&
                        testBoolean == cursor.getBoolean(columnIndex.get("t_boolean"))) {
                    rowFound = true;
                    break;
                }
            }
            assertTrue(rowFound);
        }
    }

    @Test
    public void testBucketedTableDoubleFloat()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableBucketedDoubleFloat);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        assertTableIsBucketed(tableHandle);

        ImmutableMap<ConnectorColumnHandle, Comparable<?>> bindings = ImmutableMap.<ConnectorColumnHandle, Comparable<?>>builder()
                .put(columnHandles.get(columnIndex.get("t_float")), 87.1)
                .put(columnHandles.get(columnIndex.get("t_double")), 88.2)
                .build();

        // floats and doubles are not supported, so we should see all splits
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.withFixedValues(bindings));
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), 32);

        int count = 0;
        for (ConnectorSplit split : splits) {
            try (RecordCursor cursor = recordSetProvider.getRecordSet(split, columnHandles).cursor()) {
                while (cursor.advanceNextPosition()) {
                    count++;
                }
            }
        }
        assertEquals(count, 100);
    }

    private void assertTableIsBucketed(ConnectorTableHandle tableHandle)
            throws Exception
    {
        // the bucketed test tables should have exactly 32 splits
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), 32);

        // verify all paths are unique
        Set<String> paths = new HashSet<>();
        for (ConnectorSplit split : splits) {
            assertTrue(paths.add(((HiveSplit) split).getPath()));
        }
    }

    @Test
    public void testGetRecords()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tablePartitionFormat);
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(tableHandle);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), this.partitions.size());
        for (ConnectorSplit split : splits) {
            HiveSplit hiveSplit = (HiveSplit) split;

            List<HivePartitionKey> partitionKeys = hiveSplit.getPartitionKeys();
            String ds = partitionKeys.get(0).getValue();
            String fileType = partitionKeys.get(1).getValue();
            long dummyPartition = Long.parseLong(partitionKeys.get(2).getValue());

            long rowNumber = 0;
            long completedBytes = 0;
            try (RecordCursor cursor = recordSetProvider.getRecordSet(hiveSplit, columnHandles).cursor()) {
                assertRecordCursorType(cursor, fileType);
                assertEquals(cursor.getTotalBytes(), hiveSplit.getLength());

                while (cursor.advanceNextPosition()) {
                    try {
                        assertReadFields(cursor, tableMetadata.getColumns());
                    }
                    catch (RuntimeException e) {
                        throw new RuntimeException("row " + rowNumber, e);
                    }

                    rowNumber++;

                    if (rowNumber % 19 == 0) {
                        assertTrue(cursor.isNull(columnIndex.get("t_string")));
                    }
                    else if (rowNumber % 19 == 1) {
                        assertEquals(cursor.getSlice(columnIndex.get("t_string")).toStringUtf8(), "");
                    }
                    else {
                        assertEquals(cursor.getSlice(columnIndex.get("t_string")).toStringUtf8(), "test");
                    }

                    assertEquals(cursor.getLong(columnIndex.get("t_tinyint")), 1 + rowNumber);
                    assertEquals(cursor.getLong(columnIndex.get("t_smallint")), 2 + rowNumber);
                    assertEquals(cursor.getLong(columnIndex.get("t_int")), 3 + rowNumber);

                    if (rowNumber % 13 == 0) {
                        assertTrue(cursor.isNull(columnIndex.get("t_bigint")));
                    }
                    else {
                        assertEquals(cursor.getLong(columnIndex.get("t_bigint")), 4 + rowNumber);
                    }

                    assertEquals(cursor.getDouble(columnIndex.get("t_float")), 5.1 + rowNumber, 0.001);
                    assertEquals(cursor.getDouble(columnIndex.get("t_double")), 6.2 + rowNumber);

                    if (rowNumber % 3 == 2) {
                        assertTrue(cursor.isNull(columnIndex.get("t_boolean")));
                    }
                    else {
                        assertEquals(cursor.getBoolean(columnIndex.get("t_boolean")), rowNumber % 3 != 0);
                    }

                    assertEquals(cursor.getSlice(columnIndex.get("ds")).toStringUtf8(), ds);
                    assertEquals(cursor.getSlice(columnIndex.get("file_format")).toStringUtf8(), fileType);
                    assertEquals(cursor.getLong(columnIndex.get("dummy")), dummyPartition);

                    long newCompletedBytes = cursor.getCompletedBytes();
                    assertTrue(newCompletedBytes >= completedBytes);
                    assertTrue(newCompletedBytes <= hiveSplit.getLength());
                    completedBytes = newCompletedBytes;
                }
            }
            assertTrue(completedBytes <= hiveSplit.getLength());
            assertEquals(rowNumber, 100);
        }
    }

    @Test
    public void testGetPartialRecords()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tablePartitionFormat);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), this.partitions.size());
        for (ConnectorSplit split : splits) {
            HiveSplit hiveSplit = (HiveSplit) split;

            List<HivePartitionKey> partitionKeys = hiveSplit.getPartitionKeys();
            String ds = partitionKeys.get(0).getValue();
            String fileType = partitionKeys.get(1).getValue();
            long dummyPartition = Long.parseLong(partitionKeys.get(2).getValue());

            long rowNumber = 0;
            try (RecordCursor cursor = recordSetProvider.getRecordSet(hiveSplit, columnHandles).cursor()) {
                assertRecordCursorType(cursor, fileType);
                while (cursor.advanceNextPosition()) {
                    rowNumber++;

                    assertEquals(cursor.getDouble(columnIndex.get("t_double")),  6.2 + rowNumber);
                    assertEquals(cursor.getSlice(columnIndex.get("ds")).toStringUtf8(), ds);
                    assertEquals(cursor.getSlice(columnIndex.get("file_format")).toStringUtf8(), fileType);
                    assertEquals(cursor.getLong(columnIndex.get("dummy")), dummyPartition);
                }
            }
            assertEquals(rowNumber, 100);
        }
    }

    @Test
    public void testGetRecordsUnpartitioned()
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(tableUnpartitioned);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), 1);

        for (ConnectorSplit split : splits) {
            HiveSplit hiveSplit = (HiveSplit) split;

            assertEquals(hiveSplit.getPartitionKeys(), ImmutableList.of());

            long rowNumber = 0;
            try (RecordCursor cursor = recordSetProvider.getRecordSet(split, columnHandles).cursor()) {
                assertRecordCursorType(cursor, "textfile");
                assertEquals(cursor.getTotalBytes(), hiveSplit.getLength());

                while (cursor.advanceNextPosition()) {
                    rowNumber++;

                    if (rowNumber % 19 == 0) {
                        assertTrue(cursor.isNull(columnIndex.get("t_string")));
                    }
                    else if (rowNumber % 19 == 1) {
                        assertEquals(cursor.getSlice(columnIndex.get("t_string")).toStringUtf8(), "");
                    }
                    else {
                        assertEquals(cursor.getSlice(columnIndex.get("t_string")).toStringUtf8(), "unpartitioned");
                    }

                    assertEquals(cursor.getLong(columnIndex.get("t_tinyint")), 1 + rowNumber);
                }
            }
            assertEquals(rowNumber, 100);
        }
    }

    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = ".*" + INVALID_COLUMN + ".*")
    public void testGetRecordsInvalidColumn()
            throws Exception
    {
        ConnectorTableHandle table = getTableHandle(tableUnpartitioned);
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(table, TupleDomain.<ConnectorColumnHandle>all());
        ConnectorSplit split = Iterables.getFirst(getAllSplits(splitManager.getPartitionSplits(table, partitionResult.getPartitions())), null);
        RecordSet recordSet = recordSetProvider.getRecordSet(split, ImmutableList.of(invalidColumnHandle));
        recordSet.cursor();
    }

    @Test
    public void testTypesTextFile()
            throws Exception
    {
        assertGetRecords("presto_test_types_textfile", "textfile");
    }

    @Test
    public void testTypesSequenceFile()
            throws Exception
    {
        assertGetRecords("presto_test_types_sequencefile", "sequencefile");
    }

    @Test
    public void testTypesRcText()
            throws Exception
    {
        assertGetRecords("presto_test_types_rctext", "rctext");
    }

    @Test
    public void testTypesRcBinary()
            throws Exception
    {
        assertGetRecords("presto_test_types_rcbinary", "rcbinary");
    }

    @Test(enabled = false)
    public void testTypesOrc()
            throws Exception
    {
        assertGetRecordsOptional("presto_test_types_orc", "orc");
    }

    @Test(enabled = false)
    public void testTypesParquet()
            throws Exception
    {
        assertGetRecordsOptional("presto_test_types_parquet", "parquet");
    }

    @Test(enabled = false)
    public void testTypesDwrf()
            throws Exception
    {
        assertGetRecordsOptional("presto_test_types_dwrf", "dwrf");
    }

    @Test
    public void testHiveViewsAreNotSupported()
            throws Exception
    {
        try {
            getTableHandle(view);
            fail("Expected HiveViewNotSupportedException");
        }
        catch (HiveViewNotSupportedException e) {
            assertEquals(e.getTableName(), view);
        }
    }

    @Test
    public void testHiveViewsHaveNoColumns()
            throws Exception
    {
        assertEquals(metadata.listTableColumns(SESSION, new SchemaTablePrefix(view.getSchemaName(), view.getTableName())), ImmutableMap.of());
    }

    @Test
    public void testRenameTable()
    {
        try {
            createDummyTable(temporaryRenameTableOld);

            metadata.renameTable(getTableHandle(temporaryRenameTableOld), temporaryRenameTableNew);

            assertNull(metadata.getTableHandle(SESSION, temporaryRenameTableOld));
            assertNotNull(metadata.getTableHandle(SESSION, temporaryRenameTableNew));
        }
        finally {
            dropTable(temporaryRenameTableOld);
            dropTable(temporaryRenameTableNew);
        }
    }

    @Test
    public void testTableCreation()
            throws Exception
    {
        try {
            doCreateTable();
        }
        finally {
            dropTable(temporaryCreateTable);
        }
    }

    @Test
    public void testSampledTableCreation()
            throws Exception
    {
        try {
            doCreateSampledTable();
        }
        finally {
            dropTable(temporaryCreateSampledTable);
        }
    }

    @Test
    public void testViewCreation()
    {
        try {
            verifyViewCreation();
        }
        finally {
            try {
                metadata.dropView(SESSION, temporaryCreateView);
            }
            catch (RuntimeException e) {
                // this usually occurs because the view was not created
            }
        }
    }

    private void createDummyTable(SchemaTableName tableName)
    {
        List<ColumnMetadata> columns = ImmutableList.of(new ColumnMetadata("dummy", VARCHAR, 1, false));
        ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(tableName, columns, tableOwner);
        ConnectorOutputTableHandle handle = metadata.beginCreateTable(SESSION, tableMetadata);
        metadata.commitCreateTable(handle, ImmutableList.<String>of());
    }

    private void verifyViewCreation()
    {
        // replace works for new view
        doCreateView(temporaryCreateView, true);

        // replace works for existing view
        doCreateView(temporaryCreateView, true);

        // create fails for existing view
        try {
            doCreateView(temporaryCreateView, false);
            fail("create existing should fail");
        }
        catch (ViewAlreadyExistsException e) {
            assertEquals(e.getViewName(), temporaryCreateView);
        }

        // drop works when view exists
        metadata.dropView(SESSION, temporaryCreateView);
        assertEquals(metadata.getViews(SESSION, temporaryCreateView.toSchemaTablePrefix()).size(), 0);
        assertFalse(metadata.listViews(SESSION, temporaryCreateView.getSchemaName()).contains(temporaryCreateView));

        // drop fails when view does not exist
        try {
            metadata.dropView(SESSION, temporaryCreateView);
            fail("drop non-existing should fail");
        }
        catch (ViewNotFoundException e) {
            assertEquals(e.getViewName(), temporaryCreateView);
        }

        // create works for new view
        doCreateView(temporaryCreateView, false);
    }

    private void doCreateView(SchemaTableName viewName, boolean replace)
    {
        String viewData = "test data";

        metadata.createView(SESSION, viewName, viewData, replace);

        Map<SchemaTableName, String> views = metadata.getViews(SESSION, viewName.toSchemaTablePrefix());
        assertEquals(views.size(), 1);
        assertEquals(views.get(viewName), viewData);

        assertTrue(metadata.listViews(SESSION, viewName.getSchemaName()).contains(viewName));
    }

    private void doCreateSampledTable()
            throws InterruptedException
    {
        // begin creating the table
        List<ColumnMetadata> columns = ImmutableList.<ColumnMetadata>builder()
                .add(new ColumnMetadata("sales", BIGINT, 1, false))
                .build();

        ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(temporaryCreateSampledTable, columns, tableOwner, true);
        ConnectorOutputTableHandle outputHandle = metadata.beginCreateTable(SESSION, tableMetadata);

        // write the records
        RecordSink sink = recordSinkProvider.getRecordSink(outputHandle);

        sink.beginRecord(8);
        sink.appendLong(2);
        sink.finishRecord();

        sink.beginRecord(5);
        sink.appendLong(3);
        sink.finishRecord();

        sink.beginRecord(7);
        sink.appendLong(4);
        sink.finishRecord();

        String fragment = sink.commit();

        // commit the table
        metadata.commitCreateTable(outputHandle, ImmutableList.of(fragment));

        // load the new table
        ConnectorTableHandle tableHandle = getTableHandle(temporaryCreateSampledTable);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.<ConnectorColumnHandle>builder()
                .addAll(metadata.getColumnHandles(tableHandle).values())
                .add(metadata.getSampleWeightColumnHandle(tableHandle))
                .build();
        assertEquals(columnHandles.size(), 2);

        // verify the metadata
        tableMetadata = metadata.getTableMetadata(getTableHandle(temporaryCreateSampledTable));
        assertEquals(tableMetadata.getOwner(), tableOwner);

        Map<String, ColumnMetadata> columnMap = uniqueIndex(tableMetadata.getColumns(), columnNameGetter());
        assertEquals(columnMap.size(), 1);

        assertPrimitiveField(columnMap, 0, "sales", BIGINT, false);

        // verify the data
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        assertEquals(partitionResult.getPartitions().size(), 1);
        ConnectorSplitSource splitSource = splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions());
        ConnectorSplit split = getOnlyElement(getAllSplits(splitSource));

        try (RecordCursor cursor = recordSetProvider.getRecordSet(split, columnHandles).cursor()) {
            assertRecordCursorType(cursor, "rcfile-binary");

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 2);
            assertEquals(cursor.getLong(1), 8);

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 3);
            assertEquals(cursor.getLong(1), 5);

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 4);
            assertEquals(cursor.getLong(1), 7);

            assertFalse(cursor.advanceNextPosition());
        }
    }

    private void doCreateTable()
            throws InterruptedException
    {
        // begin creating the table
        List<ColumnMetadata> columns = ImmutableList.<ColumnMetadata>builder()
                .add(new ColumnMetadata("id", BIGINT, 1, false))
                .add(new ColumnMetadata("t_string", VARCHAR, 2, false))
                .add(new ColumnMetadata("t_bigint", BIGINT, 3, false))
                .add(new ColumnMetadata("t_double", DOUBLE, 4, false))
                .add(new ColumnMetadata("t_boolean", BOOLEAN, 5, false))
                .build();

        ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(temporaryCreateTable, columns, tableOwner);
        ConnectorOutputTableHandle outputHandle = metadata.beginCreateTable(SESSION, tableMetadata);

        // write the records
        RecordSink sink = recordSinkProvider.getRecordSink(outputHandle);

        sink.beginRecord(1);
        sink.appendLong(1);
        sink.appendString("hello".getBytes(UTF_8));
        sink.appendLong(123);
        sink.appendDouble(43.5);
        sink.appendBoolean(true);
        sink.finishRecord();

        sink.beginRecord(1);
        sink.appendLong(2);
        sink.appendNull();
        sink.appendNull();
        sink.appendNull();
        sink.appendNull();
        sink.finishRecord();

        sink.beginRecord(1);
        sink.appendLong(3);
        sink.appendString("bye".getBytes(UTF_8));
        sink.appendLong(456);
        sink.appendDouble(98.1);
        sink.appendBoolean(false);
        sink.finishRecord();

        String fragment = sink.commit();

        // commit the table
        metadata.commitCreateTable(outputHandle, ImmutableList.of(fragment));

        // load the new table
        ConnectorTableHandle tableHandle = getTableHandle(temporaryCreateTable);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());

        // verify the metadata
        tableMetadata = metadata.getTableMetadata(getTableHandle(temporaryCreateTable));
        assertEquals(tableMetadata.getOwner(), tableOwner);

        Map<String, ColumnMetadata> columnMap = uniqueIndex(tableMetadata.getColumns(), columnNameGetter());

        assertPrimitiveField(columnMap, 0, "id", BIGINT, false);
        assertPrimitiveField(columnMap, 1, "t_string", VARCHAR, false);
        assertPrimitiveField(columnMap, 2, "t_bigint", BIGINT, false);
        assertPrimitiveField(columnMap, 3, "t_double", DOUBLE, false);
        assertPrimitiveField(columnMap, 4, "t_boolean", BOOLEAN, false);

        // verify the data
        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        assertEquals(partitionResult.getPartitions().size(), 1);
        ConnectorSplitSource splitSource = splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions());
        ConnectorSplit split = getOnlyElement(getAllSplits(splitSource));

        try (RecordCursor cursor = recordSetProvider.getRecordSet(split, columnHandles).cursor()) {
            assertRecordCursorType(cursor, "rcfile-binary");

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 1);
            assertEquals(cursor.getSlice(1).toStringUtf8(), "hello");
            assertEquals(cursor.getLong(2), 123);
            assertEquals(cursor.getDouble(3), 43.5);
            assertEquals(cursor.getBoolean(4), true);

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 2);
            assertTrue(cursor.isNull(1));
            assertTrue(cursor.isNull(2));
            assertTrue(cursor.isNull(3));
            assertTrue(cursor.isNull(4));

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 3);
            assertEquals(cursor.getSlice(1).toStringUtf8(), "bye");
            assertEquals(cursor.getLong(2), 456);
            assertEquals(cursor.getDouble(3), 98.1);
            assertEquals(cursor.getBoolean(4), false);

            assertFalse(cursor.advanceNextPosition());
        }
    }

    protected void assertGetRecordsOptional(String tableName, String fileType)
            throws Exception
    {
        if (metadata.getTableHandle(SESSION, new SchemaTableName(database, tableName)) != null) {
            assertGetRecords(tableName, fileType);
        }
    }

    protected void assertGetRecords(String tableName, String fileType)
            throws Exception
    {
        ConnectorTableHandle tableHandle = getTableHandle(new SchemaTableName(database, tableName));
        ConnectorTableMetadata tableMetadata = metadata.getTableMetadata(tableHandle);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(metadata.getColumnHandles(tableHandle).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        ConnectorPartitionResult partitionResult = splitManager.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        List<ConnectorSplit> splits = getAllSplits(splitManager.getPartitionSplits(tableHandle, partitionResult.getPartitions()));
        assertEquals(splits.size(), 1);
        HiveSplit hiveSplit = checkType(getOnlyElement(splits), HiveSplit.class, "split");

        long rowNumber = 0;
        long completedBytes = 0;
        try (RecordCursor cursor = recordSetProvider.getRecordSet(hiveSplit, columnHandles).cursor()) {
            assertRecordCursorType(cursor, fileType);
            assertEquals(cursor.getTotalBytes(), hiveSplit.getLength());

            while (cursor.advanceNextPosition()) {
                try {
                    assertReadFields(cursor, tableMetadata.getColumns());
                }
                catch (RuntimeException e) {
                    throw new RuntimeException("row " + rowNumber, e);
                }

                rowNumber++;
                Integer index;

                // STRING
                index = columnIndex.get("t_string");
                if ((rowNumber % 19) == 0) {
                    assertTrue(cursor.isNull(index));
                }
                else {
                    String stringValue = cursor.getSlice(index).toStringUtf8();
                    assertEquals(stringValue, ((rowNumber % 19) == 1) ? "" : "test");
                }

                // NUMBERS
                assertEquals(cursor.getLong(columnIndex.get("t_tinyint")), 1 + rowNumber);
                assertEquals(cursor.getLong(columnIndex.get("t_smallint")), 2 + rowNumber);
                assertEquals(cursor.getLong(columnIndex.get("t_int")), 3 + rowNumber);

                if ((rowNumber % 13) == 0) {
                    assertTrue(cursor.isNull(columnIndex.get("t_bigint")));
                }
                else {
                    assertEquals(cursor.getLong(columnIndex.get("t_bigint")), 4 + rowNumber);
                }

                assertEquals(cursor.getDouble(columnIndex.get("t_float")), 5.1 + rowNumber, 0.001);
                assertEquals(cursor.getDouble(columnIndex.get("t_double")), 6.2 + rowNumber);

                // BOOLEAN
                index = columnIndex.get("t_boolean");
                if ((rowNumber % 3) == 2) {
                    assertTrue(cursor.isNull(index));
                }
                else {
                    assertEquals(cursor.getBoolean(index), (rowNumber % 3) != 0);
                }

                // TIMESTAMP
                index = columnIndex.get("t_timestamp");
                if (index != null) {
                    if ((rowNumber % 17) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        long millis = new DateTime(2011, 5, 6, 7, 8, 9, 123, timeZone).getMillis();
                        assertEquals(cursor.getLong(index), millis);
                    }
                }

                // BINARY
                index = columnIndex.get("t_binary");
                if (index != null) {
                    if ((rowNumber % 23) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        assertEquals(cursor.getSlice(index).toStringUtf8(), "test binary");
                    }
                }

                /* TODO: enable these tests when the types are supported
                // DATE
                index = columnIndex.get("t_date");
                if (index != null) {
                    if ((rowNumber % 37) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        long millis = new DateTime(2013, 8, 9, 0, 0, 0, timeZone).getMillis();
                        assertEquals(cursor.getLong(index), millis);
                    }
                }

                // VARCHAR(50)
                index = columnIndex.get("t_varchar");
                if (index != null) {
                    if ((rowNumber % 39) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        String stringValue = cursor.getSlice(index).toStringUtf8();
                        assertEquals(stringValue, ((rowNumber % 39) == 1) ? "" : "test varchar");
                    }
                }

                // CHAR(25)
                index = columnIndex.get("t_char");
                if (index != null) {
                    if ((rowNumber % 41) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        String stringValue = cursor.getSlice(index).toStringUtf8();
                        assertEquals(stringValue, ((rowNumber % 41) == 1) ? "" : "test char");
                    }
                }
                */

                // MAP<STRING, STRING>
                index = columnIndex.get("t_map");
                if (index != null) {
                    if ((rowNumber % 27) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        assertEquals(cursor.getSlice(index).toStringUtf8(), "{\"test key\":\"test value\"}");
                    }
                }

                // ARRAY<STRING>
                index = columnIndex.get("t_array_string");
                if (index != null) {
                    if ((rowNumber % 29) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        assertEquals(cursor.getSlice(index).toStringUtf8(), "[\"abc\",\"xyz\",\"data\"]");
                    }
                }

                // ARRAY<STRUCT<s_string: STRING, s_double:DOUBLE>>
                index = columnIndex.get("t_array_struct");
                if (index != null) {
                    if ((rowNumber % 31) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        String expectedJson = "[" +
                                "{\"s_string\":\"test abc\",\"s_double\":0.1}," +
                                "{\"s_string\":\"test xyz\",\"s_double\":0.2}]";
                        assertEquals(cursor.getSlice(index).toStringUtf8(), expectedJson);
                    }
                }

                // MAP<INT, ARRAY<STRUCT<s_string: STRING, s_double:DOUBLE>>>
                index = columnIndex.get("t_complex");
                if (index != null) {
                    if ((rowNumber % 33) == 0) {
                        assertTrue(cursor.isNull(index));
                    }
                    else {
                        String expectedJson = "{\"1\":[" +
                                "{\"s_string\":\"test abc\",\"s_double\":0.1}," +
                                "{\"s_string\":\"test xyz\",\"s_double\":0.2}]}";
                        assertEquals(cursor.getSlice(index).toStringUtf8(), expectedJson);
                    }
                }

                // NEW COLUMN
                assertTrue(cursor.isNull(columnIndex.get("new_column")));

                long newCompletedBytes = cursor.getCompletedBytes();
                assertTrue(newCompletedBytes >= completedBytes);
                assertTrue(newCompletedBytes <= hiveSplit.getLength());
                completedBytes = newCompletedBytes;
            }
        }
        assertTrue(completedBytes <= hiveSplit.getLength());
        assertEquals(rowNumber, 100);
    }

    private void dropTable(SchemaTableName table)
    {
        try {
            metastoreClient.dropTable(table.getSchemaName(), table.getTableName());
        }
        catch (RuntimeException e) {
            // this usually occurs because the table was not created
        }
    }

    private ConnectorTableHandle getTableHandle(SchemaTableName tableName)
    {
        ConnectorTableHandle handle = metadata.getTableHandle(SESSION, tableName);
        checkArgument(handle != null, "table not found: %s", tableName);
        return handle;
    }

    private static int getSplitCount(ConnectorSplitSource splitSource)
            throws InterruptedException
    {
        int splitCount = 0;
        while (!splitSource.isFinished()) {
            List<ConnectorSplit> batch = splitSource.getNextBatch(1000);
            splitCount += batch.size();
        }
        return splitCount;
    }

    private static List<ConnectorSplit> getAllSplits(ConnectorSplitSource splitSource)
            throws InterruptedException
    {
        ImmutableList.Builder<ConnectorSplit> splits = ImmutableList.builder();
        while (!splitSource.isFinished()) {
            List<ConnectorSplit> batch = splitSource.getNextBatch(1000);
            splits.addAll(batch);
        }
        return splits.build();
    }

    private void assertRecordCursorType(RecordCursor cursor, String fileType)
    {
        assertInstanceOf(cursor, recordCursorType(fileType), fileType);
    }

    protected Class<? extends HiveRecordCursor> recordCursorType(String fileType)
    {
        switch (fileType) {
            case "rcfile-text":
            case "rctext":
                return ColumnarTextHiveRecordCursor.class;
            case "rcfile-binary":
            case "rcbinary":
                return ColumnarBinaryHiveRecordCursor.class;
        }
        return GenericHiveRecordCursor.class;
    }

    private static void assertReadFields(RecordCursor cursor, List<ColumnMetadata> schema)
    {
        for (int columnIndex = 0; columnIndex < schema.size(); columnIndex++) {
            ColumnMetadata column = schema.get(columnIndex);
            if (!cursor.isNull(columnIndex)) {
                if (BOOLEAN.equals(column.getType())) {
                    cursor.getBoolean(columnIndex);
                }
                else if (BIGINT.equals(column.getType())) {
                    cursor.getLong(columnIndex);
                }
                else if (DOUBLE.equals(column.getType())) {
                    cursor.getDouble(columnIndex);
                }
                else if (VARCHAR.equals(column.getType()) || VARBINARY.equals(column.getType())) {
                    try {
                        cursor.getSlice(columnIndex);
                    }
                    catch (RuntimeException e) {
                        throw new RuntimeException("column " + column, e);
                    }
                }
                else if (TIMESTAMP.equals(column.getType())) {
                    cursor.getLong(columnIndex);
                }
                else {
                    fail("Unknown primitive type " + columnIndex);
                }
            }
        }
    }

    private static void assertPrimitiveField(Map<String, ColumnMetadata> map, int position, String name, Type type, boolean partitionKey)
    {
        assertTrue(map.containsKey(name));
        ColumnMetadata column = map.get(name);
        assertEquals(column.getOrdinalPosition(), position);
        assertEquals(column.getType(), type, name);
        assertEquals(column.isPartitionKey(), partitionKey, name);
    }

    private static ImmutableMap<String, Integer> indexColumns(List<ConnectorColumnHandle> columnHandles)
    {
        ImmutableMap.Builder<String, Integer> index = ImmutableMap.builder();
        int i = 0;
        for (ConnectorColumnHandle columnHandle : columnHandles) {
            HiveColumnHandle hiveColumnHandle = checkType(columnHandle, HiveColumnHandle.class, "columnHandle");
            index.put(hiveColumnHandle.getName(), i);
            i++;
        }
        return index.build();
    }

    private static String randomName()
    {
        return UUID.randomUUID().toString().toLowerCase().replace("-", "");
    }

    private static Function<ColumnMetadata, String> columnNameGetter()
    {
        return new Function<ColumnMetadata, String>()
        {
            @Override
            public String apply(ColumnMetadata input)
            {
                return input.getName();
            }
        };
    }
}
