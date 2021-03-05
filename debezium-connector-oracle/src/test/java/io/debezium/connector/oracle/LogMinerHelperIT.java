/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle;

import static org.fest.assertions.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import io.debezium.connector.oracle.junit.SkipTestDependingOnAdapterNameRule;
import io.debezium.connector.oracle.junit.SkipWhenAdapterNameIsNot;
import io.debezium.connector.oracle.logminer.LogMinerHelper;
import io.debezium.connector.oracle.logminer.Scn;
import io.debezium.connector.oracle.logminer.SqlUtils;
import io.debezium.connector.oracle.util.TestHelper;
import io.debezium.embedded.AbstractConnectorTest;
import io.debezium.util.Testing;

/**
 * This subclasses common OracleConnectorIT for LogMiner adaptor
 */
@SkipWhenAdapterNameIsNot(value = SkipWhenAdapterNameIsNot.AdapterName.LOGMINER, reason = "LogMiner specific tests")
public class LogMinerHelperIT extends AbstractConnectorTest {

    @Rule
    public final TestRule skipAdapterRule = new SkipTestDependingOnAdapterNameRule();

    private static OracleConnection conn;

    @BeforeClass
    public static void beforeSuperClass() throws SQLException {
        try (OracleConnection adminConnection = TestHelper.adminConnection()) {
            adminConnection.resetSessionToCdb();
            LogMinerHelper.removeLogFilesFromMining(adminConnection);
        }

        conn = TestHelper.defaultConnection();
        conn.resetSessionToCdb();
    }

    @AfterClass
    public static void closeConnection() throws SQLException {
        if (conn != null && conn.isConnected()) {
            conn.close();
        }
    }

    @Before
    public void before() throws SQLException {
        setConsumeTimeout(TestHelper.defaultMessageConsumerPollTimeout(), TimeUnit.SECONDS);
        initializeConnectorTestFramework();
        Testing.Files.delete(TestHelper.DB_HISTORY_PATH);
    }

    @Test
    public void shouldAddRightArchivedRedoFiles() throws Exception {
        // case 1 : oldest scn = current scn
        long currentScn = LogMinerHelper.getCurrentScn(conn);
        Map<String, String> archivedRedoFiles = LogMinerHelper.getMap(conn, SqlUtils.archiveLogsQuery(currentScn, Duration.ofHours(0L)), "-1");
        assertThat(archivedRedoFiles.size() == 0).isTrue();

        // case 2: oldest scn = oldest in not cleared archive
        List<BigDecimal> oneDayArchivedNextScn = getOneDayArchivedLogNextScn(conn);
        long oldestArchivedScn = getOldestArchivedScn(oneDayArchivedNextScn);
        Map<String, Scn> archivedLogsForMining = LogMinerHelper.getArchivedLogFilesForOffsetScn(conn, oldestArchivedScn, Duration.ofHours(0L));
        assertThat(archivedLogsForMining.size() == (oneDayArchivedNextScn.size() - 1)).isTrue();

        archivedRedoFiles = LogMinerHelper.getMap(conn, SqlUtils.archiveLogsQuery(oldestArchivedScn - 1, Duration.ofHours(0L)), "-1");
        assertThat(archivedRedoFiles.size() == (oneDayArchivedNextScn.size())).isTrue();
    }

    @Test
    public void shouldAddRightRedoFiles() throws Exception {
        List<BigDecimal> oneDayArchivedNextScn = getOneDayArchivedLogNextScn(conn);
        long oldestArchivedScn = getOldestArchivedScn(oneDayArchivedNextScn);
        LogMinerHelper.setRedoLogFilesForMining(conn, oldestArchivedScn, Duration.ofHours(0L));

        // eliminate duplications
        Map<String, Scn> onlineLogFilesForMining = LogMinerHelper.getOnlineLogFilesForOffsetScn(conn, oldestArchivedScn);
        Map<String, Scn> archivedLogFilesForMining = LogMinerHelper.getArchivedLogFilesForOffsetScn(conn, oldestArchivedScn, Duration.ofHours(0L));
        List<String> archivedLogFiles = archivedLogFilesForMining.entrySet().stream()
                .filter(e -> !onlineLogFilesForMining.values().contains(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
        int archivedLogFilesCount = archivedLogFiles.size();

        Map<String, String> redoLogFiles = LogMinerHelper.getMap(conn, SqlUtils.allOnlineLogsQuery(), "-1");
        assertThat(getNumberOfAddedLogFiles(conn) == (redoLogFiles.size() + archivedLogFilesCount)).isTrue();
    }

    private Long getOldestArchivedScn(List<BigDecimal> oneDayArchivedNextScn) throws Exception {
        long oldestArchivedScn;
        Optional<BigDecimal> archivedScn = oneDayArchivedNextScn.stream().min(BigDecimal::compareTo);
        if (archivedScn.isPresent()) {
            oldestArchivedScn = archivedScn.get().longValue();
        }
        else {
            throw new Exception("cannot get oldest archived scn");
        }
        return oldestArchivedScn;
    }

    private static int getNumberOfAddedLogFiles(OracleConnection conn) throws SQLException {
        int counter = 0;
        try (PreparedStatement ps = conn.connection(false).prepareStatement("select * from V$LOGMNR_LOGS");
                ResultSet result = ps.executeQuery()) {
            while (result.next()) {
                counter++;
            }
        }
        return counter;
    }

    private List<BigDecimal> getOneDayArchivedLogNextScn(OracleConnection conn) throws SQLException {
        List<BigDecimal> allArchivedNextScn = new ArrayList<>();
        try (
                PreparedStatement st = conn.connection(false).prepareStatement("SELECT NAME AS FILE_NAME, NEXT_CHANGE# AS NEXT_CHANGE FROM V$ARCHIVED_LOG " +
                        " WHERE NAME IS NOT NULL AND FIRST_TIME >= SYSDATE - 1 AND ARCHIVED = 'YES' " +
                        " AND STATUS = 'A' ORDER BY 2");
                ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                allArchivedNextScn.add(rs.getBigDecimal(2));
            }
        }
        return allArchivedNextScn;
    }

}