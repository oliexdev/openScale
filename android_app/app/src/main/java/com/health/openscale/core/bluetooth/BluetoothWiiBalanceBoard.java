


public class BluetoothWiiBalanceBoard extends BluetoothCommunication {
    private final UUID WEIGHT_MEASUREMENT_SERVICE = UUID.fromString("idk");
    private final UUID WEIGHT_MEASUREMENT_HISTORY_CHARACTERISTIC = UUID.fromString("still idk");

    public BluetoothWiiBalanceBoard(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Wii Balance Board";
    }





























}
