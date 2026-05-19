package comm.dbms.project_2341016081;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;

public class LibraryApplication {

    // Using an in-memory database configuration to completely bypass folder lock errors on re-runs
    private static final String DB_URL = "jdbc:derby:memory:LibraryDB;create=true";

    public static void main(String[] args) {
        System.out.println("Initializing In-Memory Derby Database...");

        // Establishing connection using Try-With-Resources for automatic cleanup
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            System.out.println("Connected to in-memory database successfully.");

            // 1. CLEANUP (Kept for compatibility, though memory starts empty)
            System.out.println("Dropping old tables if they exist...");
            try { stmt.execute("DROP TABLE Loans"); } catch (SQLException e) { /* Ignore if table doesn't exist */ }
            try { stmt.execute("DROP TABLE Books"); } catch (SQLException e) { /* Ignore if table doesn't exist */ }
            try { stmt.execute("DROP TABLE Members"); } catch (SQLException e) { /* Ignore if table doesn't exist */ }

            // 2. CREATE NORMALIZED TABLES
            System.out.println("Creating normalized tables...");
            
            // Members Table
            stmt.execute("CREATE TABLE Members ("
                    + "MemberID INT GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), "
                    + "FirstName VARCHAR(50) NOT NULL, "
                    + "LastName VARCHAR(50) NOT NULL, "
                    + "Email VARCHAR(100) NOT NULL, "
                    + "JoinDate DATE DEFAULT CURRENT_DATE, "
                    + "CONSTRAINT PK_Members PRIMARY KEY (MemberID), "
                    + "CONSTRAINT UQ_Member_Email UNIQUE (Email)"
                    + ")");

            // Books Table
            stmt.execute("CREATE TABLE Books ("
                    + "BookID INT GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), "
                    + "Title VARCHAR(150) NOT NULL, "
                    + "Author VARCHAR(100) NOT NULL, "
                    + "ISBN VARCHAR(13) NOT NULL, "
                    + "PublishedYear INT, "
                    + "TotalCopies INT DEFAULT 1, "
                    + "CONSTRAINT PK_Books PRIMARY KEY (BookID), "
                    + "CONSTRAINT UQ_Book_ISBN UNIQUE (ISBN), "
                    + "CONSTRAINT CK_Positive_Copies CHECK (TotalCopies >= 0)"
                    + ")");

            // Loans Table (Junction table with foreign keys)
            stmt.execute("CREATE TABLE Loans ("
                    + "LoanID INT GENERATED ALWAYS AS IDENTITY (START WITH 1, INCREMENT BY 1), "
                    + "BookID INT NOT NULL, "
                    + "MemberID INT NOT NULL, "
                    + "LoanDate DATE DEFAULT CURRENT_DATE, "
                    + "DueDate DATE NOT NULL, "
                    + "ReturnDate DATE, "
                    + "CONSTRAINT PK_Loans PRIMARY KEY (LoanID), "
                    + "CONSTRAINT FK_Loans_Books FOREIGN KEY (BookID) REFERENCES Books(BookID), "
                    + "CONSTRAINT FK_Loans_Members FOREIGN KEY (MemberID) REFERENCES Members(MemberID), "
                    + "CONSTRAINT CK_Valid_ReturnDate CHECK (ReturnDate IS NULL OR ReturnDate >= LoanDate)"
                    + ")");

            // 3. DEFINE PERFORMANCE INDEXES
            System.out.println("Defining indexes...");
            stmt.execute("CREATE INDEX IX_Books_ISBN ON Books(ISBN)");
            stmt.execute("CREATE INDEX IX_Loans_MemberID ON Loans(MemberID)");
            stmt.execute("CREATE INDEX IX_Loans_ReturnDate ON Loans(ReturnDate)");

            // 4. POPULATE BASELINE SEED DATA
            System.out.println("Populating seed data...");
            
            // Seed Members
            stmt.execute("INSERT INTO Members (FirstName, LastName, Email, JoinDate) VALUES ('Jane', 'Doe', 'jane.doe@email.com', '2025-01-15')");
            stmt.execute("INSERT INTO Members (FirstName, LastName, Email, JoinDate) VALUES ('John', 'Smith', 'john.smith@email.com', '2025-02-20')");
            stmt.execute("INSERT INTO Members (FirstName, LastName, Email, JoinDate) VALUES ('Alice', 'Johnson', 'alice.j@email.com', '2025-03-05')");

            // Seed Books
            stmt.execute("INSERT INTO Books (Title, Author, ISBN, PublishedYear, TotalCopies) VALUES ('The Great Gatsby', 'F. Scott Fitzgerald', '9780743273565', 1925, 3)");
            stmt.execute("INSERT INTO Books (Title, Author, ISBN, PublishedYear, TotalCopies) VALUES ('To Kill a Mockingbird', 'Harper Lee', '9780446310789', 1960, 2)");
            stmt.execute("INSERT INTO Books (Title, Author, ISBN, PublishedYear, TotalCopies) VALUES ('1984', 'George Orwell', '9780451524935', 1949, 4)");

            // Seed Loans (Maps to auto-generated sequential IDs 1, 2, 3)
            stmt.execute("INSERT INTO Loans (BookID, MemberID, LoanDate, DueDate, ReturnDate) VALUES (1, 1, '2026-05-01', '2026-05-15', '2026-05-14')");
            stmt.execute("INSERT INTO Loans (BookID, MemberID, LoanDate, DueDate, ReturnDate) VALUES (2, 2, '2026-05-10', '2026-05-24', NULL)");
            stmt.execute("INSERT INTO Loans (BookID, MemberID, LoanDate, DueDate, ReturnDate) VALUES (3, 1, '2026-05-12', '2026-05-26', NULL)");

            // 5. EXECUTE VERIFICATION QUERY
            System.out.println("\n--- Verification Query Results ---");
            String query = "SELECT "
                         + "  m.FirstName || ' ' || m.LastName AS Member_Name, "
                         + "  b.Title AS Book_Title, "
                         + "  l.LoanDate, "
                         + "  l.DueDate, "
                         + "  CASE WHEN l.ReturnDate IS NULL THEN 'Active' ELSE 'Returned' END AS Status "
                         + "FROM Loans l "
                         + "JOIN Members m ON l.MemberID = m.MemberID "
                         + "JOIN Books b ON l.BookID = b.BookID";

            try (ResultSet rs = stmt.executeQuery(query)) {
                System.out.printf("%-18s | %-25s | %-10s | %-10s | %-10s%n", "Member Name", "Book Title", "Loan Date", "Due Date", "Status");
                System.out.println("--------------------------------------------------------------------------------------------");
                while (rs.next()) {
                    System.out.printf("%-18s | %-25s | %-10s | %-10s | %-10s%n",
                            rs.getString("Member_Name"),
                            rs.getString("Book_Title"),
                            rs.getDate("LoanDate"),
                            rs.getDate("DueDate"),
                            rs.getString("Status"));
                }
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // 6. SHUTDOWN DERBY ENGINE CLEANLY
        try {
            // Shuts down the specific in-memory library database instance safely
            DriverManager.getConnection("jdbc:derby:memory:LibraryDB;shutdown=true");
        } catch (SQLException se) {
            // Derby always throws state 08006 upon a successful individual database shutdown
            if ("08006".equals(se.getSQLState())) {
                System.out.println("\nDerby database instance shut down cleanly.");
            } else {
                se.printStackTrace();
            }
        }
    }
}
