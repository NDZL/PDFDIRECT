package com.ndzl.pdfdirect;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.os.StrictMode;
import android.print.PrintAttributes;
import android.print.pdf.PrintedPdfDocument;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DrawableUtils;
import android.support.v7.widget.Toolbar;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;


/*
* Tested on TC51 marshmallow and nougat
* Does not work on TC2x0 / TC25: NFC missing
* */

public class MainActivity extends AppCompatActivity {

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private String pdaBTaddrr;
    private ScanSettings mScanSettings;
    private TextView tvOutput;
    Button btPDF ;
    Button btViewPDF;

    private NfcAdapter mNfcAdapter;
    PendingIntent pendingIntent;
    IntentFilter writeTagFilters[];

    DezTagListener tagNdzlListener;
    BroadcastReceiver receiver;


    private boolean soundON=false;
    public void toggleSomething(View view) {
        soundON=!soundON;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mNfcAdapter != null) {
            if (!mNfcAdapter.isEnabled()) {
                //TODO indicate that wireless should be opened
            }
            mNfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mNfcAdapter != null) {
            mNfcAdapter.disableForegroundDispatch(this);
        }

    }


    byte[] pdfbyte;
    int pdfbyte_len=0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        setupDWProfile();
        registerDWreceiver();

        //for TC20 TC25 Nougat BSP, not needed on TC51 Nougat!
        if(Build.VERSION.SDK_INT>=24){
            try{
                Method m = StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                m.invoke(null);
            }catch(Exception e){
                e.printStackTrace();
            }
        }

        tvOutput = (TextView) findViewById(R.id.tvOutput);
        btPDF = (Button) findViewById(R.id.btPDF);
        btPDF.setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            if(scannedBarcode.length()==0)
                                                printDynamicPDF("");
                                            else
                                            {
                                                printDynamicPDF(scannedBarcode);
                                                //tvOutput.setText(tvOutput.getText()+"\nScanned <"+scannedBarcode+">");
                                                scannedBarcode="";
                                            }
                                        }
                                    }
                );

        btViewPDF = (Button)findViewById(R.id.btViewPDF);
        btViewPDF.setOnClickListener(new View.OnClickListener() {
                                         @Override
                                         public void onClick(View v) {
                                             viewDynPDF();
                                         }
                                     }
        );

        //PERMISSIONS AT RUNTIME
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 3003);


        //ASSET MANAGEMENT FOR PDF FILE
        /*
        AssetManager amPDF = getAssets();
        pdfbyte = new byte[1000000];
        try {
            InputStream isPDF = amPDF.open("zd420c.pdf");
            pdfbyte_len = isPDF.available();
            tvOutput.setText("PDF size="+pdfbyte_len+"\nInternal buffer size="+pdfbyte.length);
            isPDF.read(pdfbyte);

        } catch (IOException e) {
            tvOutput.setText("Asset PDF error");
        }
        */


        //NFC
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (mNfcAdapter == null) {
            tvOutput.setText("Switch NFC on or scan the printer BT address barcode!");
            //finish();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
            return;
        }
        else {
            mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
            pendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

            setOnTagReadListener();
        }


        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "cxnt48@zebra.com", Snackbar.LENGTH_LONG).setAction("Action", null).show();
                toggleSomething(view);
            }
        });

        pdaBTaddrr = android.provider.Settings.Secure.getString(this.getContentResolver(), "bluetooth_address");  //BluetoothAdapter.getDefaultAdapter().getAddress();
    }

    boolean scan_button_pressed=false;
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode==103)  //103==SCAN BUTTONS
            scan_button_pressed=true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        scan_button_pressed=false;
        return super.onKeyUp(keyCode, event);
    }

    Tag mytag;
    public interface DezTagListener {
        public void onTagRead(String tagRead);
    }

    @Override
    protected void onNewIntent(Intent intent){
        String action = intent.getAction();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            Tag myTag = (Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            Parcelable[] originalMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            if (originalMessages != null) {
                NdefRecord[] recs = ((NdefMessage) originalMessages[0]).getRecords();
                String text = ndefRecordToString(recs[0]);
                tagNdzlListener.onTagRead(text);
            }
        }
    }

    String nfcPrinterBTaddr = "";
    String nfcPrinterPartNumber = "";
    String nfcPrinterBTFriendlyName = "";
    public void setOnTagReadListener() {
        this.tagNdzlListener = new DezTagListener() {
            @Override
            public void onTagRead(String tagRead) {
                //Toast.makeText(this, "tag read:"+tagRead, Toast.LENGTH_LONG).show();
                String[] nfcrecs = tagRead.split("&");

                 nfcPrinterBTaddr = "N/A";
                 nfcPrinterPartNumber = "N/A";
                 nfcPrinterBTFriendlyName = "N/A";
                for (String nrec:nfcrecs) {
                    String[] strPair = nrec.split("=");
                    if(strPair[0].equals("mB"))
                        nfcPrinterBTaddr=strPair[1];
                    if(strPair[0].equals("c"))
                        nfcPrinterPartNumber = strPair[1];
                    if(strPair[0].equals("s"))
                        nfcPrinterBTFriendlyName = strPair[1];
                }

                tvOutput.setText("NFC\nBTAddr=<"+nfcPrinterBTaddr+">\nP/N=<"+nfcPrinterPartNumber+">\nBT FriendlyName=<"+nfcPrinterBTFriendlyName+">");

                if(nfcPrinterBTaddr.length()>3 && scan_button_pressed)
                    printPDFoverBTclassic(nfcPrinterBTaddr, getApplicationContext(), "/sdcard/pdf_demo_4x4.pdf");
            }


        };
    }

    public String ndefRecordToString(NdefRecord record) {
        byte[] payload = record.getPayload();
        return new String(payload);
    }


    private boolean isPrinterBusy = false;
    private int globalRSSI = 0;
    String printResult="";

    /*
    * Bluetooth Low Energy communication can only transfer small amounts of data at a time. The length of your desired printer data string should be less than 500 bytes. If you desire to print graphics or large data formats, we recommend that you store them on the printer and send down data to recall them.
    * */

    private void printPDFoverBTclassic(final String theBtMacAddress, final Context context, final String filePDFtoprint) {
        new Thread(new Runnable() {
            public void run() {
                Connection thePrinterConn = null;
                try {
                    isPrinterBusy = true;

                    thePrinterConn = new BluetoothConnection(theBtMacAddress);

                    thePrinterConn.open();

                    ////http://techdocs.zebra.com/link-os/2-13/android/content/com/zebra/sdk/device/FileUtil.html
                    ZebraPrinter printer = ZebraPrinterFactory.getInstance(thePrinterConn);
                    printer.sendFileContents(filePDFtoprint);

                    isPrinterBusy = false;
                    if (null != thePrinterConn) {
                        thePrinterConn.close();
                    }
                    printResult="PRINT OK";

                } catch (Exception e) {

                } finally {
                    isPrinterBusy = false;
                }

            }
        }).start();
    }


    final String dyn_pdf_name="/sdcard/pdf_demo_dynamic.pdf";
    private void printDynamicPDF(String codeToPrintInBarcode)
    {
        PrintAttributes attr = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A7)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
               // .setResolution(new PrintAttributes.Resolution("0", "ZEBRA", 200, 200))
                .setMinMargins(new PrintAttributes.Margins(20, 20, 20, 20))
                .build();

        PrintedPdfDocument document = new PrintedPdfDocument(this, attr);

        PdfDocument.Page page = document.startPage(0);

        ImageView content = new ImageView(this); // this.findViewById(android.R.id.content);
        content.setMaxHeight(800);
        content.setMaxWidth(800);

        Paint _p = new Paint(Paint.ANTI_ALIAS_FLAG);
        _p.setTypeface(Typeface.create("Arial",Typeface.NORMAL));
        _p.setTextSize(12f);
        _p.setColor(Color.rgb(0, 0, 0));
        _p.setStyle(Paint.Style.STROKE);
        _p.setStrokeWidth(1);
        Canvas _c = page.getCanvas();
        _c.drawRect(5, 5, 200, 150, _p);
        //_p.setStrokeWidth(0);
        _c.drawText("This is Zebra Tech. in Milano, Italy", 10.f, 20.f, _p );
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        String currentDateandTime = sdf.format(new Date());
        _c.drawText("Date: "+ currentDateandTime, 10.f, 35.f, _p );
        if(codeToPrintInBarcode.length()==0)
            _c.drawBitmap(generateZXingBarcode(currentDateandTime), 10.f, 50.f, _p);
        else
            _c.drawBitmap(generateZXingBarcode(codeToPrintInBarcode), 10.f, 50.f, _p);

        content.draw(_c );

        document.finishPage(page);

        try {
            document.writeTo(new FileOutputStream(dyn_pdf_name));
        } catch (IOException e) {}

        document.close();
        if(nfcPrinterBTaddr.length()==12)
            printPDFoverBTclassic(nfcPrinterBTaddr, getApplicationContext(), dyn_pdf_name);
        else
            tvOutput.setText("BT address not configured.\nTap on the NFC tag or scan barcode.");

    }

    void viewDynPDF(){
        File file = new File(dyn_pdf_name);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(file), "application/pdf");
        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        startActivity(intent);
    }

    Bitmap generateZXingBarcode(String tobeencoded){
        String text=tobeencoded;
        MultiFormatWriter multiFormatWriter = new MultiFormatWriter();
        try {
            BitMatrix bitMatrix = multiFormatWriter.encode(text, BarcodeFormat.PDF_417,200,100);
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            return barcodeEncoder.createBitmap(bitMatrix);
        } catch (Exception e) {  return null; }
    }

    void setupDWProfile(){
        Intent i = new Intent();
        i.setAction("com.symbol.datawedge.api.ACTION");
        String[] profiles = {"DWDemo", "PDF_DIRECT"};
        i.putExtra("com.symbol.datawedge.api.CLONE_PROFILE", profiles);
        i.setPackage("com.symbol.datawedge");
        sendBroadcast(i);

        Bundle bMain = new Bundle();
        bMain.putString("PROFILE_NAME","PDF_DIRECT");
        bMain.putString("PROFILE_ENABLED","true");
        bMain.putString("CONFIG_MODE","CREATE_IF_NOT_EXIST");

        ////////
        Bundle bConfigKEYSTROKE = new Bundle();
        bConfigKEYSTROKE.putString("PLUGIN_NAME","KEYSTROKE");
        bConfigKEYSTROKE.putString("RESET_CONFIG","true");
        Bundle bParamsKEYSTROKE = new Bundle();
        bParamsKEYSTROKE.putString("keystroke_output_enabled","false");
        bConfigKEYSTROKE.putBundle("PARAM_LIST", bParamsKEYSTROKE);
        bMain.putBundle("PLUGIN_CONFIG", bConfigKEYSTROKE);

        Intent j = new Intent();
        j.setPackage("com.symbol.datawedge");
        j.setAction("com.symbol.datawedge.api.ACTION");
        j.putExtra("com.symbol.datawedge.api.SET_CONFIG", bMain);
        sendBroadcast(j);
        ////////

        bMain = new Bundle();
        bMain.putString("PROFILE_NAME","PDF_DIRECT");
        bMain.putString("PROFILE_ENABLED","true");
        bMain.putString("CONFIG_MODE","CREATE_IF_NOT_EXIST");

        Bundle bConfigINTENT = new Bundle();
        bConfigINTENT.putString("PLUGIN_NAME","INTENT");
        //bConfigINTENT.putString("RESET_CONFIG","true");
        Bundle bParamsINTENT = new Bundle();
        int DELIVERYMODE=(int)2;
        bParamsINTENT.putString("intent_output_enabled","true");
        bParamsINTENT.putString("intent_action","com.ndzl.pdfdirect.READMYCODE");
        bParamsINTENT.putString("intent_category","android.intent.category.DEFAULT");
        bParamsINTENT.putString("intent_delivery", "2");  //0=Start Activity, 1=Start Service, 2=Broadcast
        //bParamsINTENT.putInt("intent_delivery", (int)2);  //crash, http://techdocs.zebra.com/datawedge/6-3/guide/api/setconfig/    look for "intent_delivery"
        bConfigINTENT.putBundle("PARAM_LIST", bParamsINTENT);
        bMain.putBundle("PLUGIN_CONFIG", bConfigINTENT);

        Intent k = new Intent();
        k.setPackage("com.symbol.datawedge");
        k.setAction("com.symbol.datawedge.api.ACTION");
        k.putExtra("com.symbol.datawedge.api.SET_CONFIG", bMain);
        sendBroadcast(k);

        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {        }
        ////////////X

        i = new Intent();
        i.setAction("com.symbol.datawedge.api.ACTION");
        String profilename = "PDF_DIRECT";
        i.putExtra("com.symbol.datawedge.api.SET_DEFAULT_PROFILE", profilename);
        i.setPackage("com.symbol.datawedge");
        sendBroadcast(i);
    }

    String scannedBarcode="";
    void registerDWreceiver() {

        //BARCODE RECEIVER
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, Intent intent) {

                String barcode_value = intent.getStringExtra("com.symbol.datawedge.data_string");
                String barcode_type = intent.getStringExtra("com.symbol.datawedge.label_type");
                scannedBarcode = barcode_value;
                nfcPrinterBTaddr = barcode_value;
                tvOutput.setText(tvOutput.getText()+"\nScanned BT Address <"+scannedBarcode+">");
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.symbol.datawedge.api.RESULT_ACTION");
        filter.addAction("com.ndzl.pdfdirect.READMYCODE");
        filter.addCategory("android.intent.category.DEFAULT");
        registerReceiver(receiver, filter);
    }



}
