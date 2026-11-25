

class InventoryException extends Exception {

    public InventoryException(String message) {
        super(message);
    }

    public InventoryException(String message, Throwable cause) {
        super(message, cause);
    }
}

class InvalidInputException extends InventoryException {

    public InvalidInputException(String message) {
        super(message);
    }
}

class NotFoundException extends InventoryException {

    public NotFoundException(String message) {
        super(message);
    }
}

class DuplicateEntryException extends InventoryException {

    public DuplicateEntryException(String message) {
        super(message);
    }
}

class InvalidRemovalException extends InventoryException {

    public InvalidRemovalException(String message) {
        super(message);
    }
}

class InsufficientStockException extends InventoryException {
    public InsufficientStockException(String message) {
        super(message);
    }
}

class PersistenceException extends InventoryException {

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
