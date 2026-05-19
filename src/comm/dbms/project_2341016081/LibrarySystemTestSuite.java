package comm.dbms.project_2341016081;

import java.sql.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LibrarySystemTestSuite {

    private static final String DB_URL = "jdbc:derby:memory:TestSuiteDB;create=true";

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println("       STARTING ROBUST DATABASE EDGE-CASE VALIDATION SUITE       ");
        System.out.println("=================================================================");

        setupDatabaseEnvironment();

        // Run validation edge-cases
        validateDuplicateInserts();
        validateCheckConstraints();
        validateConcurrentConflicts();

        // Perform final graceful engine destruction
        gracefulShutdownAndCleanup(true);
    }

    // ============================================================================
    // EDGE CASE VALIDATION SUITES
    // ============================================================================

    /**
     * 3a. Validate Duplicate Inserts on UNIQUE constraints
     */
    private static void validateDuplicateInserts() {
        System.out.println("\n[Edge Case 1] Validating Unique Constraint Enforcements...");
        String sql = "INSERT INTO Members (Email, Name) VALUES (?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            // First insert should pass cleanly
            pstmt.setString(1, "duplicate.test@library.com");
            pstmt.setString(2, "Alpha User");
            pstmt.executeUpdate();
            System.out.println(" -> First insertion successful.");

            // Second identical insert should fail
            System.out.println(" -> Attempting duplicate email insertion...");
            pstmt.setString(1, "duplicate.test@library.com");
            pstmt.setString(2, "Beta User");
            pstmt.executeUpdate();

        } catch (SQLException e) {
            // Derby SQLState '23505' indicates a unique/primary key violation
            if ("23505".equals(e.getSQLState())) {
                System.out.println("[VERIFIED] System rejected the duplicate insert with SQLState 23505.");
            } else {
                System.err.println("Unexpected failure: " + e.getMessage());
            }
        }
    }

    /**
     * 3b. Validate Field Level CHECK Constraints
     */
    private static void validateCheckConstraints() {
        System.out.println("\n[Edge Case 2] Validating Column CHECK Constraint Restrictions...");
        String sql = "INSERT INTO Books (Title, TotalCopies) VALUES (?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            System.out.println(" -> Attempting to load a book with negative copy balances (-3)...");
            pstmt.setString(1, "Forbidden Quantum Mechanics");
            pstmt.setInt(2, -3); // Violates CHECK (TotalCopies >= 0)
            pstmt.executeUpdate();

        } catch (SQLException e) {
            // Derby SQLState '23513' indicates a check constraint violation
            if ("23513".equals(e.getSQLState())) {
                System.out.println("[VERIFIED] System rejected negative values with SQLState 23513.");
            } else {
                System.err.println("Unexpected failure: " + e.getMessage());
            }
        }
    }

    /**
     * 3c. Validate Concurrent Lock Contention & Serialization
     */
    private static void validateConcurrentConflicts() {
        System.out.println("\n[Edge Case 3] Simulating High Concurrent Transaction Race Conditions...");
        
        // Seed a shared book to fight over
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO Books (Title, TotalCopies) VALUES ('High-Demand Novel', 1)");
        } catch (SQLException e) { e.printStackTrace(); }

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Task A: Grabs the book row and holds it open for 1.5 seconds
        Runnable transactionA = () -> {
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                conn.setAutoCommit(false);
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                
                try (PreparedStatement ps = conn.prepareStatement("UPDATE Books SET TotalCopies = TotalCopies - 1 WHERE Title = 'High-Demand Novel'")) {
                    ps.executeUpdate();
                    System.out.println("   * [Tx A] Incremented book row lock. Simulating processing delays...");
                    Thread.sleep(1500); // Hold lock open
                    conn.commit();
                    System.out.println("   * [Tx A] Committed changes successfully.");
                }
            } catch (Exception e) {
                System.err.println("Tx A Interrupted: " + e.getMessage());
            }
        };

        // Task B: Tries to access the exact same book row while Transaction A has it locked
        Runnable transactionB = () -> {
            try { Thread.sleep(200); } catch (InterruptedException e) {} // Ensure Tx A locks first
            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                conn.setAutoCommit(false);
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                
                System.out.println("   * [Tx B] Attempting to access the locked row...");
                try (PreparedStatement ps = conn.prepareStatement("UPDATE Books SET TotalCopies = TotalCopies - 1 WHERE Title = 'High-Demand Novel'")) {
                    ps.executeUpdate();
                    conn.commit();
                    System.out.println("   * [Tx B] Overcame isolation locks and committed cleanly.");
                }
            } catch (SQLException e) {
                // Derby error state '40XL1' indicates a lock timeout or deadlock avoidance
                if ("40XL1".equals(e.getSQLState()) || "40001".equals(e.getSQLState())) {
                    System.out.println("[VERIFIED] Tx B blocked or timed out correctly via serialization safety locks.");
                } else {
                    System.err.println("Tx B behavior: " + e.getMessage());
                }
            }
        };

        executor.execute(transactionA);
        executor.execute(transactionB);

        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) {}
    }

    // ============================================================================
    // RESOURCE MANAGEMENT AND ENGINE LIFECYCLE
    // ============================================================================

    private static void setupDatabaseEnvironment() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            System.out.println("Generating baseline structural schema constraints...");
            stmt.execute("CREATE TABLE Members (MemberID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, Name VARCHAR(50), Email VARCHAR(100) UNIQUE)");
            stmt.execute("CREATE TABLE Books (BookID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, Title VARCHAR(150), TotalCopies INT CONSTRAINT CK_Copies CHECK (TotalCopies >= 0))");

        } catch (SQLException e) {
            System.err.println("Database setup crashed: " + e.getMessage());
        }
    }

    /**
     * 2. IMPLEMENT GRACEFUL SHUTDOWN LOGIC
     */
    private static void gracefulShutdownAndCleanup(boolean dropDatabase) {
        System.out.println("\n=================================================================");
        System.out.println("                INITIATING GRACEFUL SHUTDOWN                     ");
        System.out.println("=================================================================");

        if (dropDatabase) {
            System.out.println("[Cleanup] Evaporating all data storage segments from memory...");
            String dropURL = "jdbc:derby:memory:TestSuiteDB;drop=true";
            try {
                DriverManager.getConnection(dropURL);
            } catch (SQLException e) {
                // Derby throws SQLState '08006' when an in-memory database is dropped successfully
                if ("08006".equals(e.getSQLState())) {
                    System.out.println("[Cleanup] In-Memory database dropped successfully.");
                } else {
                    System.err.println("[Cleanup Error] Failed to drop database: " + e.getMessage());
                }
            }
        }

        // Global Derby System Shutdown Engine call
        try {
            DriverManager.getConnection("jdbc:derby:;shutdown=true");
        } catch (SQLException se) {
            if ("XJ015".equals(se.getSQLState()) || "08006".equals(se.getSQLState())) {
                System.out.println("[Shutdown] The internal Derby micro-engine has completed its shutdown process.");
            } else {
                System.err.println("[Shutdown Error] Engine did not release thread locks cleanly: " + se.getMessage());
            }
        }
        System.out.println("Suite completed successfully.\n");
    }
}