package com.example.scannerapp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String FILE_PATH =
            "/storage/emulated/0/Documents/ShippingKey.xlsx";

    private Button removeLastButton;
    private EditText scanInput;
    private TextView counterDisplay;
    private TextView storeDisplay;
    private TextView upcDisplay;
    private TextView operatorInitialsDisplay;
    private TextView orderNumberDisplay;
    private TextView orderCounterDisplay;
    private String operatorInitials = "";
    private String currentOrder = null;
    private int orderScanCount = 0;
    private TextView descriptionDisplay;

    private int storeScanCount = 0;
    private boolean isProcessingScan = false;
    private String currentStore = null;

    // ✅ CACHED LOG FILE FIELDS
    private String cachedLogFileName = null;
    private File cachedLogFile = null;
    private String cachedDate = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        requestFileAccessIfNeeded();
        configureScanListener();
        initializeLogFile(); // ✅ initialize once

        counterDisplay.setText("Scanned at this Store: 0");
        orderCounterDisplay.setText("Scanned for this Order: 0");
    }

    private void initializeViews() {

        scanInput = findViewById(R.id.scanInput);
        storeDisplay = findViewById(R.id.storeDisplay);
        upcDisplay = findViewById(R.id.upcDisplay);
        descriptionDisplay = findViewById(R.id.descriptionDisplay);
        counterDisplay = findViewById(R.id.counterDisplay);
        orderNumberDisplay = findViewById(R.id.orderNumberDisplay);
        orderCounterDisplay = findViewById(R.id.orderCounterDisplay);

        removeLastButton = findViewById(R.id.removeLastButton);
        Button enterInitialsButton = findViewById(R.id.enterInitialsButton);
        Button enterOrderButton = findViewById(R.id.enterOrderButton);

        operatorInitialsDisplay = findViewById(R.id.operatorInitialsDisplay);

        removeLastButton.setOnClickListener(v -> removeLastLogEntry());
        enterInitialsButton.setOnClickListener(v -> showInitialsDialog());
        enterOrderButton.setOnClickListener(v -> showOrderDialog());
    }

    private void showInitialsDialog() {

        final EditText initialsInput = new EditText(this);
        initialsInput.setHint("Enter Initials");
        initialsInput.setSingleLine(true);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Operator Initials")
                .setView(initialsInput)
                .setPositiveButton("OK", (dialogInterface, i) -> {
                    operatorInitials = sanitize(initialsInput.getText().toString()).toUpperCase();
                    operatorInitialsDisplay.setText("Operator: " + operatorInitials);
                    scanInput.requestFocus();
                })
                .setNegativeButton("Cancel", (dialogInterface, i) -> scanInput.requestFocus())
                .create();

        dialog.setOnDismissListener(d -> scanInput.requestFocus());
        dialog.show();
    }

    private void showOrderDialog() {

        final EditText orderInput = new EditText(this);
        orderInput.setHint("Enter Order Number");
        orderInput.setSingleLine(true);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Order Number")
                .setView(orderInput)
                .setPositiveButton("OK", (dialogInterface, i) -> {
                    currentOrder = sanitize(orderInput.getText().toString());
                    orderScanCount = 0;

                    orderNumberDisplay.setText("Order Number: " + currentOrder);
                    orderCounterDisplay.setText("Scanned for this Order: 0");

                    scanInput.requestFocus();
                })
                .setNegativeButton("Cancel", (dialogInterface, i) -> scanInput.requestFocus())
                .create();

        dialog.setOnDismissListener(d -> scanInput.requestFocus());
        dialog.show();
    }

    private void requestFileAccessIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {

            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void configureScanListener() {
        scanInput.requestFocus();

        scanInput.setOnKeyListener((v, keyCode, event) -> {
            if (!isProcessingScan &&
                    keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.getAction() == KeyEvent.ACTION_DOWN) {

                isProcessingScan = true;
                processScanInput();
                return true;
            }
            return false;
        });
    }

    private void processScanInput() {

        if (operatorInitials == null || operatorInitials.isEmpty()) {
            upcDisplay.setText("OPERATOR INITIALS REQUIRED");
            descriptionDisplay.setText("Press 'Enter Initials' before scanning");
            resetScanState();
            return;
        }

        if (currentOrder == null || currentOrder.isEmpty()) {
            upcDisplay.setText("ORDER NUMBER REQUIRED");
            descriptionDisplay.setText("Press 'Enter Order #' before scanning");
            resetScanState();
            return;
        }

        String scannedValue = sanitize(scanInput.getText().toString());

        if (scannedValue.isEmpty()) {
            resetScanState();
            return;
        }

        if (!isValidScan(scannedValue)) {
            showInvalidScanMessage();
            resetScanState();
            return;
        }

        if (isStoreCode(scannedValue)) {
            activateStore(scannedValue);
            resetScanState();
            return;
        }

        if (!isStoreSelected()) {
            showNoStoreSelectedMessage();
            resetScanState();
            return;
        }

        processUpcScan(scannedValue, true);
        resetScanState();
    }

    private void processUpcScan(String upc, boolean shouldLog) {

        vibrateSuccess();

        boolean found = displayItemDetailsForUpc(upc);

        incrementScanCounters();

        if (shouldLog) {
            logScan(currentStore, currentOrder, upc, found);
        }
    }

    private boolean displayItemDetailsForUpc(String upc) {

        boolean found = searchExcelByUPC(upc);

        if (!found) {
            displayItemNotFound(upc);
        }

        return found;
    }

    private void incrementScanCounters() {
        storeScanCount++;
        orderScanCount++;

        counterDisplay.setText("Scanned at this Store: " + storeScanCount);
        orderCounterDisplay.setText("Scanned for this Order: " + orderScanCount);
    }

    private void decrementScanCounters() {
        if (storeScanCount > 0) storeScanCount--;
        if (orderScanCount > 0) orderScanCount--;

        counterDisplay.setText("Scanned at this Store: " + storeScanCount);
        orderCounterDisplay.setText("Scanned for this Order: " + orderScanCount);
    }

    private void removeLastLogEntry() {

        try {

            File logFile = getTodayLogFile();

            if (logFile == null || !logFile.exists()) {
                descriptionDisplay.setText("No log file found");
                return;
            }

            ArrayList<String> lines = readAllLines(logFile);

            if (lines.size() <= 1) {
                descriptionDisplay.setText("Nothing to remove");
                return;
            }

            lines.remove(lines.size() - 1);
            writeAllLines(logFile, lines);

            decrementScanCounters();
            refreshDisplayFromLastLogEntry(lines);

            vibratePattern(new long[]{0, 200, 100, 200});

        } catch (Exception e) {
            descriptionDisplay.setText("REMOVE ERROR");
            e.printStackTrace();
        }
    }

    private void refreshDisplayFromLastLogEntry(ArrayList<String> lines) {

        if (lines.size() <= 1) {
            clearItemDisplay();
            return;
        }

        String lastLine = lines.get(lines.size() - 1);
        String[] parts = lastLine.split(",");

        if (parts.length >= 3) {
            String upc = sanitize(parts[2]);
            displayItemDetailsForUpc(upc);
        }
    }

    private void activateStore(String storeCode) {

        if (currentStore == null || !currentStore.equals(storeCode)) {

            currentStore = storeCode;
            storeScanCount = 0;

            storeDisplay.setText("Store: " + currentStore);
            counterDisplay.setText("Scanned at this Store: 0");

            clearItemDisplay();
            vibrateStoreSet();
        }
    }

    private boolean isStoreSelected() {
        return currentStore != null;
    }

    private boolean isStoreCode(String value) {
        return value.startsWith("HD");
    }

    private boolean isValidScan(String value) {
        return value.length() >= 8 || value.startsWith("HD");
    }

    private void displayItemNotFound(String upc) {
        upcDisplay.setText("UPC: " + upc);
        descriptionDisplay.setText("Item not found");
    }

    private void clearItemDisplay() {
        upcDisplay.setText("SKU:\nUPC:");
        descriptionDisplay.setText("Description:\nSize:");
    }

    private void showInvalidScanMessage() {
        upcDisplay.setText("INVALID SCAN");
        descriptionDisplay.setText("Must be 8+ characters or start with HD");
    }

    private void showNoStoreSelectedMessage() {
        upcDisplay.setText("ERROR: No Store Selected");
        descriptionDisplay.setText("");
    }

    private void resetScanState() {
        scanInput.setText("");
        isProcessingScan = false;
        scanInput.requestFocus();
    }

    private boolean searchExcelByUPC(String scannedUPC) {

        File file = new File(FILE_PATH);
        if (!file.exists()) {
            descriptionDisplay.setText("File not found");
            return false;
        }

        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheet("bySKU");
            if (sheet == null) {
                descriptionDisplay.setText("Sheet not found");
                return false;
            }

            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (Row row : sheet) {

                if (row.getRowNum() == 0) continue;

                String excelUPC = sanitize(
                        formatter.formatCellValue(row.getCell(1), evaluator)
                );

                if (excelUPC.equals(scannedUPC)) {

                    upcDisplay.setText(
                            "SKU: " + formatter.formatCellValue(row.getCell(0), evaluator) +
                                    "\nUPC: " + excelUPC
                    );

                    descriptionDisplay.setText(
                            "Description: " + formatter.formatCellValue(row.getCell(2), evaluator) +
                                    "\nSize: " + formatter.formatCellValue(row.getCell(3), evaluator)
                    );

                    return true;
                }
            }

        } catch (Exception e) {
            descriptionDisplay.setText("Error reading file");
        }

        return false;
    }

    private void logScan(String store, String order, String upc, boolean found) {

        try {

            File logFile = getTodayLogFile();
            if (logFile == null) {
                descriptionDisplay.setText("Log folder issue");
                return;
            }

            boolean newFile = !logFile.exists();
            FileWriter writer = new FileWriter(logFile, true);

            if (newFile) {
                writer.write("Store,Order,UPC,Date,Time,Result,Initials\n");
            }

            String currentDate = new SimpleDateFormat("MM-dd-yyyy", Locale.US).format(new Date());
            String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

            writer.write(store + "," +
                    order + "," +
                    upc + "," +
                    currentDate + "," +
                    currentTime + "," +
                    (found ? "FOUND" : "NOT FOUND") + "," +
                    operatorInitials + "\n");

            writer.flush();
            writer.close();

        } catch (Exception e) {
            descriptionDisplay.setText("LOG ERROR: " + e.getMessage());
        }
    }

    // CACHED VERSION
    private File getTodayLogFile() {
        String today = new SimpleDateFormat("MM-dd-yyyy", Locale.US).format(new Date());
        if (cachedLogFile != null && today.equals(cachedDate)) {
        return cachedLogFile;
    }
        else{
            File baseFile = new File(FILE_PATH);
            File parentDir = baseFile.getParentFile();

            if (parentDir == null || !parentDir.exists()) return null;





            File aFile = new File(parentDir, "iamscanner2");
            boolean useScanner2 = aFile.exists() && aFile.isFile();

            cachedLogFileName = today + (useScanner2 ? " Scanner 2 Log.csv" : " Scanner Log.csv");
            cachedLogFile = new File(parentDir, cachedLogFileName);
            cachedDate = today;

            return cachedLogFile;

        }
    }

    private void initializeLogFile() {
        getTodayLogFile();
    }

    private ArrayList<String> readAllLines(File file) throws Exception {
        ArrayList<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        String line;
        while ((line = reader.readLine()) != null) lines.add(line);
        reader.close();
        return lines;
    }

    private void writeAllLines(File file, ArrayList<String> lines) throws Exception {
        BufferedWriter writer = new BufferedWriter(new FileWriter(file, false));
        for (String line : lines) {
            writer.write(line);
            writer.newLine();
        }
        writer.flush();
        writer.close();
    }

    private String sanitize(String input) {
        return input == null ? "" : input.replaceAll("\\p{C}", "").trim();
    }

    private void vibrateSuccess() {
        vibratePattern(new long[]{0, 250});
    }

    private void vibrateStoreSet() {
        vibratePattern(new long[]{0, 100, 100, 100});
    }

    private void vibratePattern(long[] pattern) {

        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }
}