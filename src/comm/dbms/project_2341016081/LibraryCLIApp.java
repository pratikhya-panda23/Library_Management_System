package comm.dbms.project_2341016081;

import java.sql.*;
import java.time.LocalDate;
import java.util.Scanner;

public class LibraryCLIApp {

    // Switched to an in-memory configuration to enable error-free execution re-runs
    private static final String DB_URL = "jdbc:derby:memory:LibraryCLIDB;create=true";
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("Initializing Database Environment...");
        setupDatabaseStructure();

        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();

            try {
                switch (choice) {
                    case "1": registerMember(); break;
                    case "2": addBook(); break;
                    case "3": processLoanEngine(); break;
                    case "4": processReturnEngine(); break;
                    case "5": queryActiveLoansByMember(); break;
                    case "6": queryOverdueBooks(); break;
                    case "7": runPerformanceBenchmarks(); break;
                    case "8":
                        running = false;
                        System.out.println("\nExiting application. Shutting down database engine cleanly...");
                        break;
                    default:
                        System.out.println("\nInvalid choice. Please select an option between 1 and 8.");
                }
            } catch (Exception e) {
                System.err.println("\n[System Error] Execution failed: " + e.getMessage());
            }
        }

        // Clean In-Memory Derby Instance Shutdown
        try {
            DriverManager.getConnection("jdbc:derby:memory:LibraryCLIDB;shutdown=true");
        } catch (SQLException se) {
            if ("08006".equals(se.getSQLState())) {
                System.out.println("Database engine shut down cleanly. Goodbye!");
            } else {
                System.err.println("Error shutting down database engine: " + se.getMessage());
            }
        }
    }

    private static void printMenu() {
        System.out.println("\n==================================================");
        System.out.println("          LIBRARY MANAGEMENT SYSTEM CLI           ");
        System.out.println("==================================================");
        System.out.println("1. Register a Member");
        System.out.println("2. Add a New Book");
        System.out.println("3. Process a Book Loan");
        System.out.println("4. Process a Book Return");
        System.out.println("5. Query Active Loans by Member");
        System.out.println("6. Query Overdue Books");
        System.out.println("7. Run Database Performance Benchmarks");
        System.out.println("8. Exit Application");
        System.out.println("==================================================");
        System.out.print("Enter your choice (1-8): ");
    }

    // ============================================================================
    // MENU ACTIONS (Implementing Try-With-Resources & Parameterized Queries)
    // ============================================================================

    private static void registerMember() {
        System.out.print("\nEnter First Name: ");
        String first = scanner.nextLine().trim();
        System.out.print("Enter Last Name: ");
        String last = scanner.nextLine().trim();
        System.out.print("Enter Email Address: ");
        String email = scanner.nextLine().trim();

        String sql = "INSERT INTO Members (FirstName, LastName, Email) VALUES (?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, first);
            pstmt.setString(2, last);
            pstmt.setString(3, email);
            pstmt.executeUpdate();

            System.out.println("[SUCCESS] Member registered successfully!");
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to register member: " + e.getMessage());
        }
    }

    private static void addBook() {
        System.out.print("\nEnter Book Title: ");
        String title = scanner.nextLine().trim();
        System.out.print("Enter Author Name: ");
        String author = scanner.nextLine().trim();
        System.out.print("Enter 13-digit ISBN: ");
        String isbn = scanner.nextLine().trim();
        System.out.print("Enter Quantity Stocked: ");
        int qty = Integer.parseInt(scanner.nextLine().trim());

        String sql = "INSERT INTO Books (Title, Author, ISBN, TotalCopies) VALUES (?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, title);
            pstmt.setString(2, author);
            pstmt.setString(3, isbn);
            pstmt.setInt(4, qty);
            pstmt.executeUpdate();

            System.out.println("[SUCCESS] Book added to catalog inventory!");
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to add book: " + e.getMessage());
        }
    }

    private static void processLoanEngine() {
        System.out.print("\nEnter Member ID: ");
        int memberId = Integer.parseInt(scanner.nextLine().trim());
        System.out.print("Enter Book ID: ");
        int bookId = Integer.parseInt(scanner.nextLine().trim());

        String checkSql = "SELECT TotalCopies FROM Books WHERE BookID = ?";
        String decBookSql = "UPDATE Books SET TotalCopies = TotalCopies - 1 WHERE BookID = ?";
        String insLoanSql = "INSERT INTO Loans (BookID, MemberID, DueDate) VALUES (?, ?, ?)";
        String incMemberSql = "UPDATE Members SET ActiveLoanCount = ActiveLoanCount + 1 WHERE MemberID = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false); // Enable manual explicit transaction control

            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql);
                 PreparedStatement decBookStmt = conn.prepareStatement(decBookSql);
                 PreparedStatement insLoanStmt = conn.prepareStatement(insLoanSql);
                 PreparedStatement incMemberStmt = conn.prepareStatement(incMemberSql)) {

                // Step 1: Availability Check
                checkStmt.setInt(1, bookId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next() || rs.getInt("TotalCopies") <= 0) {
                        System.out.println("[ABORTED] This book is out of stock or does not exist.");
                        conn.rollback();
                        return;
                    }
                }

                // Step 2: Update Book Copies
                decBookStmt.setInt(1, bookId);
                decBookStmt.executeUpdate();

                // Step 3: Insert Loan Record (Due in 14 days)
                insLoanStmt.setInt(1, bookId);
                insLoanStmt.setInt(2, memberId);
                insLoanStmt.setDate(3, Date.valueOf(LocalDate.now().plusDays(14)));
                insLoanStmt.executeUpdate();

                // Step 4: Increase Active Loan Count
                incMemberStmt.setInt(1, memberId);
                incMemberStmt.executeUpdate();

                conn.commit();
                System.out.println("[SUCCESS] Transaction complete! Book loaned successfully.");
            } catch (SQLException ex) {
                conn.rollback();
                System.err.println("[TRANSACTION ROLLBACK] Error occurred during processing: " + ex.getMessage());
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Database connection failed: " + e.getMessage());
        }
    }

    private static void processReturnEngine() {
        System.out.print("\nEnter Loan ID to return: ");
        int loanId = Integer.parseInt(scanner.nextLine().trim());

        String findLoanSql = "SELECT BookID, MemberID FROM Loans WHERE LoanID = ? AND ReturnDate IS NULL";
        String updateLoanSql = "UPDATE Loans SET ReturnDate = ? WHERE LoanID = ?";
        String incBookSql = "UPDATE Books SET TotalCopies = TotalCopies + 1 WHERE BookID = ?";
        String decMemberSql = "UPDATE Members SET ActiveLoanCount = ActiveLoanCount - 1 WHERE MemberID = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false);

            try (PreparedStatement findStmt = conn.prepareStatement(findLoanSql);
                 PreparedStatement updLoanStmt = conn.prepareStatement(updateLoanSql);
                 PreparedStatement incBookStmt = conn.prepareStatement(incBookSql);
                 PreparedStatement decMemberStmt = conn.prepareStatement(decMemberSql)) {

                findStmt.setInt(1, loanId);
                int bookId = -1, memberId = -1;
                try (ResultSet rs = findStmt.executeQuery()) {
                    if (rs.next()) {
                        bookId = rs.getInt("BookID");
                        memberId = rs.getInt("MemberID");
                    } else {
                        System.out.println("[ABORTED] No active loan found matching that ID.");
                        conn.rollback();
                        return;
                    }
                }

                // Close Loan Record
                updLoanStmt.setDate(1, Date.valueOf(LocalDate.now()));
                updLoanStmt.setInt(2, loanId);
                updLoanStmt.executeUpdate();

                // Restock Book
                incBookStmt.setInt(1, bookId);
                incBookStmt.executeUpdate();

                // Decrement Member Count
                decMemberStmt.setInt(1, memberId);
                decMemberStmt.executeUpdate();

                conn.commit();
                System.out.println("[SUCCESS] Transaction complete! Return executed cleanly.");
            } catch (SQLException ex) {
                conn.rollback();
                System.err.println("[TRANSACTION ROLLBACK] Return failed: " + ex.getMessage());
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Connection error: " + e.getMessage());
        }
    }

    private static void queryActiveLoansByMember() {
        System.out.print("\nEnter Member ID to inspect: ");
        int memberId = Integer.parseInt(scanner.nextLine().trim());

        String sql = "SELECT l.LoanID, b.Title, l.LoanDate, l.DueDate " +
                     "FROM Loans l JOIN Books b ON l.BookID = b.BookID " +
                     "WHERE l.MemberID = ? AND l.ReturnDate IS NULL";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, memberId);
            try (ResultSet rs = pstmt.executeQuery()) {
                System.out.printf("\n%-10s | %-30s | %-12s | %-12s%n", "Loan ID", "Book Title", "Loaned Date", "Due Date");
                System.out.println("----------------------------------------------------------------------------");
                boolean dataFound = false;
                while (rs.next()) {
                    dataFound = true;
                    System.out.printf("%-10d | %-30s | %-12s | %-12s%n",
                            rs.getInt("LoanID"),
                            rs.getString("Title"),
                            rs.getDate("LoanDate"),
                            rs.getDate("DueDate"));
                }
                if (!dataFound) System.out.println("No active loans tracked for this member ID.");
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to query active loans: " + e.getMessage());
        }
    }

    private static void queryOverdueBooks() {
        String sql = "SELECT l.LoanID, m.FirstName || ' ' || m.LastName AS Name, b.Title, l.DueDate " +
                     "FROM Loans l JOIN Members m ON l.MemberID = m.MemberID " +
                     "JOIN Books b ON l.BookID = b.BookID " +
                     "WHERE l.ReturnDate IS NULL AND l.DueDate < ?";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setDate(1, Date.valueOf(LocalDate.now())); // Checked against current running execution date
            try (ResultSet rs = pstmt.executeQuery()) {
                System.out.printf("\n%-10s | %-20s | %-30s | %-12s%n", "Loan ID", "Borrower", "Book Title", "Due Date");
                System.out.println("-----------------------------------------------------------------------------------------");
                boolean dataFound = false;
                while (rs.next()) {
                    dataFound = true;
                    System.out.printf("%-10d | %-20s | %-30s | %-12s%n",
                            rs.getInt("LoanID"),
                            rs.getString("Name"),
                            rs.getString("Title"),
                            rs.getDate("DueDate"));
                }
                if (!dataFound) System.out.println("Excellent! There are currently zero overdue items in circulation.");
            }
        } catch (SQLException e) {
            System.err.println("[ERROR] Overdue indexing failed: " + e.getMessage());
        }
    }

    // ============================================================================
    // 4. PERFORMANCE BENCHMARKS ENGINE
    // ============================================================================
    private static void runPerformanceBenchmarks() {
        System.out.println("\n[Benchmark Engine] Preparing mock structural dataset (10,000 Records)...");
        String insertSql = "INSERT INTO Books (Title, Author, ISBN, TotalCopies) VALUES (?, ?, ?, ?)";
        String selectIndexedSql = "SELECT * FROM Books WHERE ISBN = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false); // Use batch processing for population speed

            // 1. Write Benchmark
            long startWrite = System.currentTimeMillis();
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (int i = 1; i <= 10000; i++) {
                    pstmt.setString(1, "Mock Volume " + i);
                    pstmt.setString(2, "Author " + i);
                    pstmt.setString(3, String.format("978%010d", i)); // Padded continuous standard ISBN
                    pstmt.setInt(4, 5);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
            }
            long endWrite = System.currentTimeMillis();
            System.out.printf(" -> Time taken to batch insert 10,000 Records: %d ms%n", (endWrite - startWrite));

            // 2. Read Benchmark (Targeting Indexed Columns)
            long startRead = System.currentTimeMillis();
            try (PreparedStatement pstmt = conn.prepareStatement(selectIndexedSql)) {
                // Perform 1000 targeted reads randomly down the indexed list structure
                for (int j = 1; j <= 1000; j++) {
                    pstmt.setString(1, String.format("978%010d", j * 7)); // Jump indexes
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) { /* Consume Result Set buffer memory */ }
                    }
                }
            }
            long endRead = System.currentTimeMillis();
            System.out.printf(" -> Time taken to index scan 1,000 queries: %d ms%n", (endRead - startRead));

        } catch (SQLException e) {
            System.err.println("[BENCHMARK ERROR] Aborted metrics routine: " + e.getMessage());
        }
    }

    // ============================================================================
    // INITIALIZATION ROUTINE
    // ============================================================================
    private static void setupDatabaseStructure() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            // Table structural validations
            try { stmt.execute("CREATE TABLE Members (MemberID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, FirstName VARCHAR(50), LastName VARCHAR(50), Email VARCHAR(100) UNIQUE, ActiveLoanCount INT DEFAULT 0, JoinDate DATE DEFAULT CURRENT_DATE)"); } catch (SQLException e) {}
            try { stmt.execute("CREATE TABLE Books (BookID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, Title VARCHAR(150), Author VARCHAR(100), ISBN VARCHAR(13) UNIQUE, TotalCopies INT DEFAULT 1)"); } catch (SQLException e) {}
            try { stmt.execute("CREATE TABLE Loans (LoanID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, BookID INT REFERENCES Books(BookID), MemberID INT REFERENCES Members(MemberID), LoanDate DATE DEFAULT CURRENT_DATE, DueDate DATE, ReturnDate DATE)"); } catch (SQLException e) {}

            // Indexes for speed enhancement
            try { stmt.execute("CREATE INDEX IX_Books_ISBN ON Books(ISBN)"); } catch (SQLException e) {}
            try { stmt.execute("CREATE INDEX IX_Loans_MemberID ON Loans(MemberID)"); } catch (SQLException e) {}
            try { stmt.execute("CREATE INDEX IX_Loans_ReturnDate ON Loans(ReturnDate)"); } catch (SQLException e) {}

        } catch (SQLException e) {
            System.err.println("Database setup initialization failed: " + e.getMessage());
        }
    }
}