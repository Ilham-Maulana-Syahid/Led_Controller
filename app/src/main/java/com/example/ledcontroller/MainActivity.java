package com.example.ledcontroller;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "LEDController";
    private static final int REQUEST_LOCAL_NETWORK = 1;

    private static final Pattern POLA_VOLTAGE = Pattern.compile("Voltage:\\s*([0-9.]+)");

    TextView textStatus, textIntensity, textVoltage, textPotentio;
    EditText editIp, editPort, editIntensity;
    Button btnConnect, btnSetIntensity, btnLed1, btnLed2, btnLed3;

    ZContext context;
    ZMQ.Socket pushSocket;
    ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    ExecutorService recvExecutor = Executors.newSingleThreadExecutor();
    volatile int generation = 0;

    int selectedLed = 0;
    int intensity = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        context = new ZContext();

        textStatus = findViewById(R.id.textStatus);
        textIntensity = findViewById(R.id.textIntensity);
        textVoltage = findViewById(R.id.textVoltage);
        textPotentio = findViewById(R.id.textPotentio);
        editIp = findViewById(R.id.editIp);
        editPort = findViewById(R.id.editPort);
        editIntensity = findViewById(R.id.editIntensity);
        btnConnect = findViewById(R.id.btnConnect);
        btnSetIntensity = findViewById(R.id.btnSetIntensity);
        btnLed1 = findViewById(R.id.btnLed1);
        btnLed2 = findViewById(R.id.btnLed2);
        btnLed3 = findViewById(R.id.btnLed3);

        btnConnect.setOnClickListener(v -> connect());
        btnSetIntensity.setOnClickListener(v -> aturIntensitas());

        btnLed1.setOnClickListener(v -> pilihLed(1));
        btnLed2.setOnClickListener(v -> pilihLed(2));
        btnLed3.setOnClickListener(v -> pilihLed(3));
    }

    private void pilihLed(int nomor) {
        selectedLed = nomor;
        kirimCommand();
    }

    private void aturIntensitas() {
        String teks = editIntensity.getText().toString().trim();
        if (teks.isEmpty()) {
            Toast.makeText(this, "Isi angka 0 - 100", Toast.LENGTH_SHORT).show();
            return;
        }

        int nilai;
        try {
            nilai = Integer.parseInt(teks);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Angka tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }

        if (nilai < 0 || nilai > 100) {
            Toast.makeText(this, "Angka harus 0 - 100", Toast.LENGTH_SHORT).show();
            return;
        }

        intensity = nilai;
        textIntensity.setText("INTENSITAS: " + intensity + "%");
        kirimCommand();
    }

    private void kirimCommand() {
        final int pwmValue = Math.round(intensity * 255.0f / 100.0f);
        final String pwmCommand = "PWM:" + pwmValue;
        final String ledCommand = selectedLed == 0 ? null : "LED" + selectedLed + ":ON";
        final String message = ledCommand == null
                ? pwmCommand
                : pwmCommand + ", " + ledCommand;
        Log.d(TAG, "Command: " + message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

        sendExecutor.execute(() -> {
            try {
                if (pushSocket == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Tekan CONNECT dulu", Toast.LENGTH_SHORT).show());
                    return;
                }
                pushSocket.send(pwmCommand);
                if (ledCommand != null) {
                    pushSocket.send(ledCommand);
                }
            } catch (Exception e) {
                Log.e(TAG, "Gagal kirim command STM32", e);
            }
        });
    }

    private void connect() {
        if (Build.VERSION.SDK_INT >= 37
                && checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            textStatus.setText("STATUS: Meminta izin jaringan lokal...");
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_LOCAL_NETWORK},
                    REQUEST_LOCAL_NETWORK
            );
            return;
        }

        connectToBridge();
    }

    private void connectToBridge() {
        final String ip = editIp.getText().toString().trim();
        final int port;
        try {
            port = Integer.parseInt(editPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Port tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }

        final int myGen = ++generation;
        textStatus.setText("STATUS: Menghubungkan...");

        sendExecutor.execute(() -> {
            ZMQ.Socket statusSocket = null;
            try {
                if (pushSocket != null) {
                    pushSocket.close();
                }
                pushSocket = context.createSocket(SocketType.PUSH);
                pushSocket.setLinger(0);
                pushSocket.connect("tcp://" + ip + ":" + port);
                Log.d(TAG, "PUSH ke tcp://" + ip + ":" + port);

                statusSocket = context.createSocket(SocketType.REQ);
                statusSocket.setLinger(0);
                statusSocket.setSendTimeOut(3000);
                statusSocket.setReceiveTimeOut(3000);
                statusSocket.connect("tcp://" + ip + ":" + (port + 2));
                statusSocket.send("PING");
                String response = statusSocket.recvStr();
                if (!"PONG".equals(response)) {
                    throw new IllegalStateException("Bridge tidak mengonfirmasi koneksi");
                }

                Log.d(TAG, "Bridge terkonfirmasi di tcp://" + ip + ":" + (port + 2));
                runOnUiThread(() -> {
                    if (myGen == generation) {
                        textStatus.setText("STATUS: Terhubung ke " + ip + ":" + port);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Gagal mengonfirmasi koneksi bridge", e);
                if (pushSocket != null) {
                    pushSocket.close();
                    pushSocket = null;
                }
                runOnUiThread(() -> {
                    if (myGen == generation) {
                        textStatus.setText("STATUS: Gagal terhubung ke bridge");
                    }
                });
            } finally {
                if (statusSocket != null) {
                    statusSocket.close();
                }
            }
        });

        recvExecutor.execute(() -> {
            ZMQ.Socket subSocket = null;
            try {
                subSocket = context.createSocket(SocketType.SUB);
                subSocket.setLinger(0);
                subSocket.setReceiveTimeOut(500);
                subSocket.connect("tcp://" + ip + ":" + (port + 1));
                subSocket.subscribe("");
                Log.d(TAG, "SUB ke tcp://" + ip + ":" + (port + 1));

                while (myGen == generation) {
                    String pesan = subSocket.recvStr();
                    if (pesan != null) {
                        tampilkanData(pesan);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "SUB berhenti: " + e.getMessage());
            } finally {
                try {
                    if (subSocket != null) {
                        subSocket.close();
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCAL_NETWORK) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectToBridge();
            } else {
                textStatus.setText("STATUS: Izin jaringan lokal ditolak");
                Toast.makeText(
                        this,
                        "Izinkan akses jaringan lokal agar aplikasi dapat terhubung ke bridge",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void tampilkanData(String pesan) {
        Matcher mV = POLA_VOLTAGE.matcher(pesan);

        if (mV.find()) {
            final String tegangan = mV.group(1);
            runOnUiThread(() -> textVoltage.setText(tegangan + " V"));

            try {
                int voltageBucket = (int) Double.parseDouble(tegangan);
                if (voltageBucket >= 0 && voltageBucket <= 3) {
                    int adcMin = (int) Math.ceil(voltageBucket * 4095.0 / 3.3);
                    int adcMax = voltageBucket == 3
                            ? 4095
                            : (int) Math.ceil((voltageBucket + 1) * 4095.0 / 3.3) - 1;
                    runOnUiThread(() -> textPotentio.setText(
                            adcMin + " - " + adcMax + " / 4095"
                    ));
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Data tegangan tidak dapat dipakai untuk menentukan rentang ADC: " + tegangan, e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
        sendExecutor.execute(() -> {
            try {
                if (pushSocket != null) {
                    pushSocket.close();
                }
                context.close();
            } catch (Exception ignored) {
            }
        });
        sendExecutor.shutdown();
        recvExecutor.shutdown();
    }
}