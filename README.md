# Fresh Grocery Expiry Watch

A console based Java project to manage grocery stock when the same product comes in different batches with different expiry dates.

## Features

- Add products and add new batches of stock
- View all products and batch wise inventory
- Record a sale (the batch that expires first is sold first - FEFO, expired batches are skipped)
- See expired stock and near-expiry stock (number of alert days can be changed)
- Remove damaged or expired stock
- Inventory, sales and wastage reports
- Transaction history
- Data is saved in CSV files so it is still there after closing the program

## Java concepts used

Collections (HashMap, List), OOP (encapsulation, inheritance, abstraction, polymorphism), Comparator, Streams, LocalDate, BigDecimal, file handling and custom exceptions.

## How to run

You need JDK 17 or above.

Windows:

```
build.bat
run.bat
```

Linux / Mac:

```
chmod +x build.sh run.sh
./build.sh
./run.sh
```

On the first run the program asks if you want to load sample data (y/n). Your data gets saved in a `data` folder next to the project.

## Folders

| Folder | What is inside |
|---|---|
| (root) | Main.java - starts the program |
| model | Product, Batch, Category, StockStatus, Transaction, SaleTransaction, SaleLine, StockRemoval, RemovalReason |
| service | InventoryManager, SalesManager, StockRemovalService, ExpiryService, TransactionManager, ConfigService, PersistenceService, SampleDataLoader, AppServices |
| comparator | FefoComparator - sorts batches by expiry date |
| report | Inventory, Expiry, Sales and Wastage reports, ReportGenerator |
| repository | saving and loading CSV files |
| ui | ConsoleMenu and ConsoleInput |
| exception | custom exceptions |
| util | MoneyUtil, DateUtil, CsvUtil, InputValidator, TablePrinter |

## Limitations

- Console only, one user at a time
- Products and batches cannot be edited or deleted from the menu
- One product per sale, quantity is a whole number
