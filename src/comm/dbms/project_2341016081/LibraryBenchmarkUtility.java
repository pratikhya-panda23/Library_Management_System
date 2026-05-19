package comm.dbms.project_2341016081;


import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class LibraryBenchmarkUtility {

    private static final String DB_URL = "jdbc:derby:memory:BenchmarkDB;create=true";
    private static final int ITERATIONS = 5;

    private static class BenchmarkResult {
        String operationType;
        String complexity;
        double avgTimeMs;
        double throughput;
        String observations;

        BenchmarkResult(String op, String comp, double ms, double tp, String obs) {
            this.operationType = op;
            this.complexity = comp;
            this.avgTimeMs = ms;
            this.throughput = tp;
            this.observations = obs;
        }
    }

    public static void main(String[] args) {
        System.out.println("Initializing Isolation Benchmarking Database Instance...");
        setupTables();

        List<BenchmarkResult> report = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            
            // ============================================================================
            // WARM-UP PHASE
            // ============================================================================
            System.out.println("\n[Warm-up] Initiating JVM JIT & Engine Cache Warm-up...");
            runWarmup(conn);
            System.out.println("[Warm-up] State stabilized.");

            // ============================================================================
            // INSERT STRATEGY COMPARISON
            // ============================================================================
            System.out.println("\n[Benchmarking] Running Insert Strategy Suites...");
            report.add(benchIndividualInsert(conn, 1000));
            report.add(benchBatchInsert(conn, 1000));
            report.add(benchBatchInsert(conn, 10000));

            // ============================================================================
            // STATEMENT TYPE COMPARISON
            // ============================================================================
            System.out.println("[Benchmarking] Running Statement Architecture Suites...");
            report.add(benchStandardStatement(conn, 1000));
            report.add(benchPreparedStatement(conn, 1000));

            // ============================================================================
            // TRANSACTION GRANULARITY COMPARISON
            // ============================================================================
            System.out.println("[Benchmarking] Running Transaction Granularity Suites...");
            report.add(benchPerOperationCommit(conn, 100));
            report.add(benchBatchedCommit(conn, 100));

            // ============================================================================
            // QUERY STRATEGY COMPARISON
            // ============================================================================
            System.out.println("[Benchmarking] Running Query Access Path Suites...");
            seedLoansForQueryBench(conn, 5000);
            report.add(benchTableScan(conn, 500));
            report.add(benchIndexedLookup(conn, 500));

            // FIX: Safely commit anything lingering and restore auto-commit status before closing connection
            if (!conn.getAutoCommit()) {
                conn.commit();
                conn.setAutoCommit(true);
            }

            // ============================================================================
            // GENERATE STRUCTURED REPORT
            // ============================================================================
            printFinalReport(report);

        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Clean In-Memory Instance Shutdown
        try { 
            DriverManager.getConnection("jdbc:derby:memory:BenchmarkDB;shutdown=true"); 
        } catch (SQLException se) {
            if ("08006".equals(se.getSQLState())) {
                System.out.println("\nIn-memory benchmarking engine shut down cleanly.");
            }
        }
    }

    // ============================================================================
    // BENCHMARK RUNNERS
    // ============================================================================

    private static BenchmarkResult benchIndividualInsert(Connection conn, int records) throws SQLException {
        conn.setAutoCommit(true);
        long totalTime = 0;

        for (int r = 0; r < ITERATIONS; r++) {
            clearTable(conn, "Books");
            long start = System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO Books (Title, ISBN) VALUES (?, ?)")) {
                for (int i = 0; i < records; i++) {
                    ps.setString(1, "Book " + i);
                    ps.setString(2, "ISBN" + i);
                    ps.executeUpdate();
                }
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Individual Insert", records + " Records", avgMs, records / (avgMs / 1000.0), 
                "High auto-commit overhead; forces disk flushes on every record.");
    }

    private static BenchmarkResult benchBatchInsert(Connection conn, int records) throws SQLException {
        conn.setAutoCommit(false); 
        long totalTime = 0;

        for (int r = 0; r < ITERATIONS; r++) {
            clearTable(conn, "Books");
            long start = System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO Books (Title, ISBN) VALUES (?, ?)")) {
                for (int i = 0; i < records; i++) {
                    ps.setString(1, "Book " + i);
                    ps.setString(2, "ISBN" + i);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Batch Insert", records + " Records", avgMs, records / (avgMs / 1000.0), 
                "Drastic speedup; single transactional network/io write array block.");
    }

    private static BenchmarkResult benchStandardStatement(Connection conn, int lookups) throws SQLException {
        conn.setAutoCommit(true);
        long totalTime = 0;

        for (int r = 0; r < ITERATIONS; r++) {
            long start = System.nanoTime();
            try (Statement stmt = conn.createStatement()) {
                for (int i = 0; i < lookups; i++) {
                    String sql = "SELECT * FROM Books WHERE ISBN = 'ISBN" + (i % 100) + "'";
                    try (ResultSet rs = stmt.executeQuery(sql)) { while(rs.next()){} }
                }
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Statement (Concat)", lookups + " Lookups", avgMs, lookups / (avgMs / 1000.0), 
                "Suffers from engine compilation overhead on every unique query string.");
    }

    private static BenchmarkResult benchPreparedStatement(Connection conn, int lookups) throws SQLException {
        conn.setAutoCommit(true);
        long totalTime = 0;

        for (int r = 0; r < ITERATIONS; r++) {
            long start = System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM Books WHERE ISBN = ?")) {
                for (int i = 0; i < lookups; i++) {
                    ps.setString(1, "ISBN" + (i % 100));
                    try (ResultSet rs = ps.executeQuery()) { while(rs.next()){} }
                }
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Prepared Statement", lookups + " Lookups", avgMs, lookups / (avgMs / 1000.0), 
                "Faster performance due to single initial compilation and plan reuse.");
    }

    private static BenchmarkResult benchPerOperationCommit(Connection conn, int operations) throws SQLException {
        long totalTime = 0;
        for (int r = 0; r < ITERATIONS; r++) {
            long start = System.nanoTime();
            for (int i = 0; i < operations; i++) {
                conn.setAutoCommit(true); 
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO Books (Title, ISBN) VALUES (?, ?)")) {
                    ps.setString(1, "TxBook " + i);
                    ps.setString(2, "TxISBN" + i + "_" + r);
                    ps.executeUpdate();
                }
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Per-Op Commit", operations + " Ops", avgMs, operations / (avgMs / 1000.0), 
                "Heavy write locks and constant log sync operations slowing down processing.");
    }

    private static BenchmarkResult benchBatchedCommit(Connection conn, int operations) throws SQLException {
        long totalTime = 0;
        for (int r = 0; r < ITERATIONS; r++) {
            long start = System.nanoTime();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO Books (Title, ISBN) VALUES (?, ?)")) {
                for (int i = 0; i < operations; i++) {
                    ps.setString(1, "TxBook " + i);
                    ps.setString(2, "TxISBN" + i + "_" + r);
                    ps.executeUpdate();
                }
                conn.commit();
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Batched Commit (100)", operations + " Ops", avgMs, operations / (avgMs / 1000.0), 
                "Significantly minimizes IO bottlenecks by locking once per batch.");
    }

    private static BenchmarkResult benchTableScan(Connection conn, int lookups) throws SQLException {
        conn.setAutoCommit(true);
        long totalTime = 0;
        String sql = "SELECT * FROM Loans WHERE LOWER(Notes) = ?";
        
        for (int r = 0; r < ITERATIONS; r++) {
            long start = System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < lookups; i++) {
                    ps.setString(1, "standard text notes details " + (i % 10));
                    try (ResultSet rs = ps.executeQuery()) { while(rs.next()){} }
                }
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Full Table Scan", lookups + " Queries", avgMs, lookups / (avgMs / 1000.0), 
                "Scales poorly; engine sequentially processes all table pages from disk.");
    }

    private static BenchmarkResult benchIndexedLookup(Connection conn, int lookups) throws SQLException {
        conn.setAutoCommit(true);
        long totalTime = 0;
        String sql = "SELECT * FROM Loans WHERE ReturnDate = ?";

        for (int r = 0; r < ITERATIONS; r++) {
            long start = System.nanoTime();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < lookups; i++) {
                    ps.setDate(1, Date.valueOf(LocalDate.now().plusDays(i % 5)));
                    try (ResultSet rs = ps.executeQuery()) { while(rs.next()){} }
                }
            }
            totalTime += (System.nanoTime() - start);
        }
        double avgMs = (totalTime / (double) ITERATIONS) / 1_000_000.0;
        return new BenchmarkResult("Indexed Lookup", lookups + " Queries", avgMs, lookups / (avgMs / 1000.0), 
                "O(log N) operations; uses B-Tree index structure to fetch page pointers directly.");
    }

    // ============================================================================
    // UTILITIES, WARMUP, AND SETUP
    // ============================================================================

    private static void runWarmup(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO Books (Title, ISBN) VALUES (?, ?)")) {
            for (int i = 0; i < 300; i++) {
                ps.setString(1, "Warmup " + i);
                ps.setString(2, "W_ISBN" + i);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM Books WHERE ISBN = ?")) {
            for (int i = 0; i < 300; i++) {
                ps.setString(1, "W_ISBN" + i);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()){} }
            }
        }
        clearTable(conn, "Books");
        conn.commit();
    }

    private static void setupTables() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            try { stmt.execute("DROP TABLE Loans"); } catch (SQLException e) {}
            try { stmt.execute("DROP TABLE Books"); } catch (SQLException e) {}

            stmt.execute("CREATE TABLE Books (BookID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, Title VARCHAR(150), ISBN VARCHAR(50))");
            stmt.execute("CREATE TABLE Loans (LoanID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, ReturnDate DATE, Notes VARCHAR(250))");
            stmt.execute("CREATE INDEX IX_Loans_ReturnDate ON Loans(ReturnDate)");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void seedLoansForQueryBench(Connection conn, int count) throws SQLException {
        conn.setAutoCommit(false);
        String sql = "INSERT INTO Loans (ReturnDate, Notes) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                ps.setDate(1, Date.valueOf(LocalDate.now().plusDays(i % 5)));
                ps.setString(2, "standard text notes details " + (i % 10));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        }
    }

    private static void clearTable(Connection conn, String tableName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM " + tableName);
        }
    }

    private static void printFinalReport(List<BenchmarkResult> results) {
        System.out.println("\n" + "=" .repeat(125));
        System.out.printf("%-22s | %-18s | %-20s | %-18s | %-35s%n", 
                "Operation Type", "Complexity", "Avg Execution Time", "Throughput", "Observations & Anomalies");
        System.out.printf("%-22s | %-18s | %-20s | %-18s | %-35s%n", 
                "", "", "(ms)", "(ops/sec)", "");
        System.out.println("=" .repeat(125));

        for (BenchmarkResult res : results) {
            System.out.printf("%-22s | %-18s | %-20.3f | %-18.2f | %-35s%n",
                    res.operationType,
                    res.complexity,
                    res.avgTimeMs,
                    res.throughput,
                    res.observations);
        }
        System.out.println("=" .repeat(125));
    }
}