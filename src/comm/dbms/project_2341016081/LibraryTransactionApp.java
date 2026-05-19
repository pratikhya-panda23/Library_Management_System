package comm.dbms.project_2341016081;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Savepoint;
import java.sql.Date;
import java.time.LocalDate;

public class LibraryTransactionApp {

    // Configured to run purely in memory to eliminate file lock collisions on continuous re-runs
    private static final String DB_URL = "jdbc:derby:memory:LibraryTransactionDB;create=true";

    public static void main(String[] args) {
        setupDatabase();

        // Establish connection to run transaction demonstrations
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            
            // 1. DISABLE AUTO-COMMIT
            conn.setAutoCommit(false);
            System.out.println("\n[Transaction System] Auto-commit disabled. Manual control active.");

            System.out.println("\n==================================================");
            System.out.println("TEST 1: Successful Multi-Step Business Operation");
            System.out.println("==================================================");
            // Member 1 borrows Book 1 (The Great Gatsby - 3 copies available)
            processLoan(conn, 1, 1);
            printState(conn, "Post-Test 1 State");

            System.out.println("\n==================================================");
            System.out.println("TEST 2: Full Rollback (Book Out of Stock)");
            System.out.println("==================================================");
            // Forcing availability check failure by requesting a non-existent book or depleted book
            processLoan(conn, 99, 2); 
            printState(conn, "Post-Test 2 State (Should match Post-Test 1)");

            System.out.println("\n==================================================");
            System.out.println("TEST 3: Savepoint & Full Rollback (Constraint Violation)");
            System.out.println("==================================================");
            // Member 3 borrows Book 3. We will try to simulate an error after a savepoint to trigger a rollback.
            processLoanSimulateFailure(conn, 3, 3);
            printState(conn, "Post-Test 3 State (Should show no changes from Test 1)");

        } catch (SQLException e) {
            System.err.println("Database connection error: " + e.getMessage());
        }

        // Clean database instance shutdown
        try { 
            DriverManager.getConnection("jdbc:derby:memory:LibraryTransactionDB;shutdown=true"); 
        } catch (SQLException se) {
            if ("08006".equals(se.getSQLState())) {
                System.out.println("\nIn-memory transaction database instance shut down cleanly.");
            }
        }
    }

    /**
     * 2. IMPLEMENT MULTI-STEP BUSINESS OPERATION
     * 3. WRAP IN TRY-CATCH WITH COMMIT & ROLLBACK
     * 4. ADD SAVEPOINT SUPPORT FOR PARTIAL ROLLBACK
     */
    public static void processLoan(Connection conn, int bookId, int memberId) {
        String checkBookSql = "SELECT TotalCopies FROM Books WHERE BookID = ?";
        String decrementBookSql = "UPDATE Books SET TotalCopies = TotalCopies - 1 WHERE BookID = ?";
        String insertLoanSql = "INSERT INTO Loans (BookID, MemberID, DueDate) VALUES (?, ?, ?)";
        String updateMemberSql = "UPDATE Members SET ActiveLoanCount = ActiveLoanCount + 1 WHERE MemberID = ?";

        Savepoint loanSavepoint = null;

        try (PreparedStatement checkBookStmt = conn.prepareStatement(checkBookSql);
             PreparedStatement decrementBookStmt = conn.prepareStatement(decrementBookSql);
             PreparedStatement insertLoanStmt = conn.prepareStatement(insertLoanSql);
             PreparedStatement updateMemberStmt = conn.prepareStatement(updateMemberSql)) {

            // Step A: Verify book availability
            checkBookStmt.setInt(1, bookId);
            try (ResultSet rs = checkBookStmt.executeQuery()) {
                if (!rs.next() || rs.getInt("TotalCopies") <= 0) {
                    throw new SQLException("Operation Aborted: Book ID " + bookId + " is not available or out of stock.");
                }
            }

            // Step B: Update book inventory status
            decrementBookStmt.setInt(1, bookId);
            decrementBookStmt.executeUpdate();
            System.out.println("[Step B Complete] Decremented available copies for Book ID: " + bookId);

            // Set Savepoint right before dealing with sensitive User Accounts/Logistics records
            loanSavepoint = conn.setSavepoint("LoanInsertionSavepoint");
            System.out.println("[Savepoint Created] 'LoanInsertionSavepoint'");

            // Step C: Insert loan record
            insertLoanStmt.setInt(1, bookId);
            insertLoanStmt.setInt(2, memberId);
            insertLoanStmt.setDate(3, Date.valueOf(LocalDate.now().plusDays(14))); // Due in 14 days
            insertLoanStmt.executeUpdate();
            System.out.println("[Step C Complete] Injected Loan tracking record.");

            // Step D: Update member active loan count
            updateMemberStmt.setInt(1, memberId);
            updateMemberStmt.executeUpdate();
            System.out.println("[Step D Complete] Updated Member profile active loan metrics.");

            // Commit the entire atomic unit of work if everything passes successfully
            conn.commit();
            System.out.println("[SUCCESS] Transaction Committed cleanly for processing.");

        } catch (SQLException e) {
            System.err.println("[ERROR] Business logic failed: " + e.getMessage());
            
            try {
                if (loanSavepoint != null && !conn.getAutoCommit()) {
                    System.err.println("[Rollback] Attempting partial rollback to savepoint...");
                    conn.rollback(loanSavepoint);
                    System.err.println("[Rollback] Partial rollback to savepoint completed successfully.");
                }
                
                System.err.println("[Rollback] Performing absolute transaction rollback to guarantee zero structural leakage...");
                conn.rollback();
                System.err.println("[Rollback] Complete rollback executed successfully. Database state preserved.");
            } catch (SQLException ex) {
                System.err.println("Critically failed during rollback process: " + ex.getMessage());
            }
        }
    }

    /**
     * 5. DEMONSTRATE TRANSACTION ISOLATION & CONSTRAINT VIOLATIONS
     */
    public static void processLoanSimulateFailure(Connection conn, int bookId, int memberId) {
        Savepoint customSavepoint = null;
        try (PreparedStatement decBook = conn.prepareStatement("UPDATE Books SET TotalCopies = TotalCopies - 1 WHERE BookID = ?");
             PreparedStatement insLoan = conn.prepareStatement("INSERT INTO Loans (BookID, MemberID, DueDate) VALUES (?, ?, ?)");
             PreparedStatement triggerError = conn.prepareStatement("UPDATE Members SET ActiveLoanCount = -5 WHERE MemberID = ?")) { 

            // Update Book
            decBook.setInt(1, bookId);
            decBook.executeUpdate();

            // Set Savepoint
            customSavepoint = conn.setSavepoint("CustomSavepoint");

            // Insert Loan
            insLoan.setInt(1, bookId);
            insLoan.setInt(2, memberId);
            insLoan.setDate(3, Date.valueOf(LocalDate.now().plusDays(14)));
            insLoan.executeUpdate();

            System.out.println("[Simulation] Forcing a Check Constraint Violation on Members table...");
            triggerError.setInt(1, memberId);
            triggerError.executeUpdate(); // This WILL throw an exception due to CHECK constraint!

            conn.commit();
        } catch (SQLException e) {
            System.err.println("[Expected Exception Caught] " + e.getMessage());
            try {
                System.err.println("[Rollback Actions Active] Erasing transaction modifications entirely...");
                conn.rollback(); 
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
    }

    // Helper method to set up the initial test database state
    private static void setupDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            try { stmt.execute("DROP TABLE Loans"); } catch (SQLException e) {}
            try { stmt.execute("DROP TABLE Books"); } catch (SQLException e) {}
            try { stmt.execute("DROP TABLE Members"); } catch (SQLException e) {}

            stmt.execute("CREATE TABLE Members ("
                    + "MemberID INT GENERATED ALWAYS AS IDENTITY, "
                    + "FirstName VARCHAR(50), LastName VARCHAR(50), Email VARCHAR(100), "
                    + "ActiveLoanCount INT DEFAULT 0, "
                    + "CONSTRAINT PK_Members PRIMARY KEY (MemberID), "
                    + "CONSTRAINT CK_NonNegative_Loans CHECK (ActiveLoanCount >= 0))");

            stmt.execute("CREATE TABLE Books ("
                    + "BookID INT GENERATED ALWAYS AS IDENTITY, "
                    + "Title VARCHAR(150), TotalCopies INT DEFAULT 1, "
                    + "CONSTRAINT PK_Books PRIMARY KEY (BookID))");

            stmt.execute("CREATE TABLE Loans ("
                    + "LoanID INT GENERATED ALWAYS AS IDENTITY, "
                    + "BookID INT REFERENCES Books(BookID), "
                    + "MemberID INT REFERENCES Members(MemberID), "
                    + "DueDate DATE)");

            stmt.execute("INSERT INTO Members (FirstName, LastName, Email) VALUES ('Jane', 'Doe', 'jane@email.com')");
            stmt.execute("INSERT INTO Members (FirstName, LastName, Email) VALUES ('John', 'Smith', 'john@email.com')");
            stmt.execute("INSERT INTO Members (FirstName, LastName, Email) VALUES ('Alice', 'Jan', 'alice@email.com')");

            stmt.execute("INSERT INTO Books (Title, TotalCopies) VALUES ('The Great Gatsby', 3)");
            stmt.execute("INSERT INTO Books (Title, TotalCopies) VALUES ('To Kill a Mockingbird', 0)");
            stmt.execute("INSERT INTO Books (Title, TotalCopies) VALUES ('1984', 4)");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Helper method to print data states for validation
    private static void printState(Connection conn, String label) {
        System.out.println("\n--- Current Data State: " + label + " ---");
        try (Statement stmt = conn.createStatement()) {
            System.out.println("[Books Status]");
            try (ResultSet rs = stmt.executeQuery("SELECT BookID, Title, TotalCopies FROM Books")) {
                while(rs.next()) {
                    System.out.printf("  ID: %d | Title: %-22s | Remaining Copies: %d%n", 
                            rs.getInt("BookID"), rs.getString("Title"), rs.getInt("TotalCopies"));
                }
            }
            System.out.println("[Members Loan Count Metrics]");
            try (ResultSet rs = stmt.executeQuery("SELECT MemberID, FirstName, ActiveLoanCount FROM Members")) {
                while(rs.next()) {
                    System.out.printf("  Member ID: %d | Name: %-6s | Active Loans Stored: %d%n", 
                            rs.getInt("MemberID"), rs.getString("FirstName"), rs.getInt("ActiveLoanCount"));
                }
            }
            System.out.println("[Total Inserted Active System Loans]");
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS total FROM Loans")) {
                if (rs.next()) System.out.println("  Total Loans Records in Database: " + rs.getInt("total"));
            }
        } catch (SQLException e) {
            System.err.println("Error outputting data state metrics: " + e.getMessage());
        }
    }
}