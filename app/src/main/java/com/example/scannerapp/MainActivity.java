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
    private String operatorInitials = "";
    private TextView descriptionDisplay;
    private TextView sizeDisplay;

    private int storeScanCount = 0;
    private boolean isProcessingScan = false;
    private String currentStore = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        requestFileAccessIfNeeded();
        configureScanListener();

        counterDisplay.setText("Scanned at this Store: 0");
    }

    private void initializeViews() {

        scanInput = findViewById(R.id.scanInput);
        storeDisplay = findViewById(R.id.storeDisplay);
        upcDisplay = findViewById(R.id.upcDisplay);
        descriptionDisplay = findViewById(R.id.descriptionDisplay);
        sizeDisplay = findViewById(R.id.sizeDisplay);
        counterDisplay = findViewById(R.id.counterDisplay);

        removeLastButton = findViewById(R.id.removeLastButton);
        Button enterInitialsButton = findViewById(R.id.enterInitialsButton);

        operatorInitialsDisplay = findViewById(R.id.operatorInitialsDisplay);

        removeLastButton.setOnClickListener(v -> removeLastLogEntry());

        enterInitialsButton.setOnClickListener(v -> showInitialsDialog());
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
                .setNegativeButton("Cancel", (dialogInterface, i) -> {
                    scanInput.requestFocus();
                })
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

    // ==============================
    // SCAN PROCESSING
    // ==============================

    private void processScanInput() {

        if (operatorInitials == null || operatorInitials.isEmpty()) { // CHANGE

            upcDisplay.setText("OPERATOR INITIALS REQUIRED"); // CHANGE
            descriptionDisplay.setText("Press 'Enter Initials' before scanning"); // CHANGE
            sizeDisplay.setText(""); // CHANGE

            resetScanState(); // CHANGE
            return; // CHANGE
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

        if (found) {
            incrementScanCounter();
        }

        if (shouldLog) {
            logScan(currentStore, upc, found);
        }
    }

    private boolean displayItemDetailsForUpc(String upc) {

        boolean found = searchExcelByUPC(upc);

        if (!found) {
            displayItemNotFound(upc);
        }

        return found;
    }

    // ==============================
    // REMOVE LAST LOG ENTRY
    // ==============================

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

            // Remove last scan row
            lines.remove(lines.size() - 1);
            writeAllLines(logFile, lines);

            decrementScanCounter();

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

        if (parts.length >= 2) {
            String upc = sanitize(parts[1]);
            displayItemDetailsForUpc(upc);
        }
    }

    // ==============================
    // STORE HANDLING
    // ==============================

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

    // ==============================
    // DISPLAY HELPERS
    // ==============================

    private void displayItemNotFound(String upc) {
        upcDisplay.setText("UPC: " + upc);
        descriptionDisplay.setText("Item not found");
        sizeDisplay.setText("");
    }

    private void clearItemDisplay() {
        upcDisplay.setText("SKU:\nUPC:");
        descriptionDisplay.setText("Description:\nSize:");
        sizeDisplay.setText("Shipping Container:\nTray Qty:\nCC Cart Qty:\nEZ Rack Qty:\nInput Qty:");
    }

    private void showInvalidScanMessage() {
        upcDisplay.setText("INVALID SCAN");
        descriptionDisplay.setText("Must be 8+ characters or start with HD");
        sizeDisplay.setText("");
    }

    private void showNoStoreSelectedMessage() {
        upcDisplay.setText("ERROR: No Store Selected");
        descriptionDisplay.setText("");
        sizeDisplay.setText("");
    }

    private void incrementScanCounter() {
        storeScanCount++;
        counterDisplay.setText("Scanned at this Store: " + storeScanCount);
    }

    private void decrementScanCounter() {
        if (storeScanCount > 0) {
            storeScanCount--;
            counterDisplay.setText("Scanned at this Store: " + storeScanCount);
        }
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
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator(); // CHANGE

            for (Row row : sheet) {

                if (row.getRowNum() == 0) continue;

                String excelUPC = sanitize(
                        formatter.formatCellValue(row.getCell(1), evaluator) // CHANGE
                );

                if (excelUPC.equals(scannedUPC)) {

                    upcDisplay.setText(
                            "SKU: " + formatter.formatCellValue(row.getCell(0), evaluator) + // CHANGE
                                    "\nUPC: " + excelUPC
                    );

                    descriptionDisplay.setText(
                            "Description: " + formatter.formatCellValue(row.getCell(2), evaluator) + // CHANGE
                                    "\nSize: " + formatter.formatCellValue(row.getCell(3), evaluator) // CHANGE
                    );

                    sizeDisplay.setText(
                            "Shipping Container: " + formatter.formatCellValue(row.getCell(4), evaluator) + // CHANGE
                                    "\nTray Qty: " + formatter.formatCellValue(row.getCell(5), evaluator) + // CHANGE
                                    "\nCC Cart Qty: " + formatter.formatCellValue(row.getCell(6), evaluator) + // CHANGE
                                    "\nEZ Rack Qty: " + formatter.formatCellValue(row.getCell(7), evaluator) + // CHANGE
                                    "\nInput Qty: " + formatter.formatCellValue(row.getCell(8), evaluator) // CHANGE
                    );

                    return true;
                }
            }

        } catch (Exception e) {
            descriptionDisplay.setText("Error reading file");
        }

        return false;
    }

    // ==============================
    // LOGGING
    // ==============================

    private void logScan(String store, String upc, boolean found) {

        try {

            File logFile = getTodayLogFile();
            if (logFile == null) {
                descriptionDisplay.setText("Log folder issue");
                return;
            }

            boolean newFile = !logFile.exists();
            FileWriter writer = new FileWriter(logFile, true);

            if (newFile) {
                writer.write("Store,UPC,Date,Time,Result,Initials\n");
            }

            String currentDate = new SimpleDateFormat("MM-dd-yyyy", Locale.US)
                    .format(new Date());

            String currentTime = new SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new Date());

            writer.write(store + "," +
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

    private File getTodayLogFile() {

        File baseFile = new File(FILE_PATH);
        File parentDir = baseFile.getParentFile();

        if (parentDir == null || !parentDir.exists()) {
            return null;
        }

        String fileDate = new SimpleDateFormat("MM-dd-yyyy", Locale.US)
                .format(new Date());

        // Check for file named "a" in the same directory
        File aFile = new File(parentDir, "iamscanner2"); // CHANGE
        boolean useScanner2 = aFile.exists() && aFile.isFile(); // CHANGE

        String logName = fileDate + (useScanner2 ? " Scanner 2 Log.csv" : " Scanner Log.csv"); // CHANGE

        return new File(parentDir, logName); // CHANGE
    }

    private ArrayList<String> readAllLines(File file) throws Exception {

        ArrayList<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(file));

        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }

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

        android.os.Vibrator vibrator =
                (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);

        if (vibrator == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(pattern, -1)
            );
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }
}
