package com.lexi.rfid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import me.jahnen.libaums.core.UsbMassStorageDevice;
import me.jahnen.libaums.core.fs.FileSystem;
import me.jahnen.libaums.core.fs.UsbFile;
import me.jahnen.libaums.core.fs.UsbFileInputStream;
import me.jahnen.libaums.core.partition.Partition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsbStorageBrowserActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.lexi.rfid.USB_STORAGE_PERMISSION";

    private UsbManager usbManager;
    private UsbMassStorageDevice storageDevice;
    private FileSystem fileSystem;
    private UsbFile currentDir;
    private final Stack<UsbFile> dirStack = new Stack<>();

    private RecyclerView recyclerView;
    private FileAdapter fileAdapter;
    private TextView tvPath, tvStatus;
    private View progressBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (device != null) mountDevice(device);
                } else {
                    showError("USB permission denied. Tap the back button and try again.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                showError("USB drive disconnected.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_storage);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("USB Memory Browser");
        }

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        tvPath = findViewById(R.id.tvPath);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerView);

        fileAdapter = new FileAdapter();
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setAdapter(fileAdapter);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbPermissionReceiver, filter);

        detectAndMount();
    }

    private void detectAndMount() {
        setStatus("Looking for USB memory drive...");
        UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
        if (devices.length == 0) {
            showError("No USB memory drive found.\n\nMake sure:\n• The USB drive is plugged in via a USB-C OTG adapter\n• The drive is FAT32 or exFAT formatted\n• Try unplugging and re-plugging the drive");
            return;
        }
        UsbMassStorageDevice device = devices[0];
        UsbDevice usbDevice = device.getUsbDevice();
        if (usbManager.hasPermission(usbDevice)) {
            mountDevice(usbDevice);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(usbDevice, pi);
            setStatus("Waiting for USB permission...");
        }
    }

    private void mountDevice(UsbDevice usbDevice) {
        setStatus("Mounting USB drive...");
        showProgress(true);
        executor.execute(() -> {
            try {
                UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
                UsbMassStorageDevice target = null;
                for (UsbMassStorageDevice d : devices) {
                    if (d.getUsbDevice().getDeviceId() == usbDevice.getDeviceId()) {
                        target = d;
                        break;
                    }
                }
                if (target == null) {
                    showError("Could not find USB mass storage device.");
                    return;
                }
                target.init();
                storageDevice = target;

                List<Partition> partitions = target.getPartitions();
                if (partitions.isEmpty()) {
                    showError("No readable partitions found on USB drive.\n\nMake sure the drive is FAT32 or exFAT formatted.");
                    return;
                }
                fileSystem = partitions.get(0).getFileSystem();
                currentDir = fileSystem.getRootDirectory();
                List<UsbFile> files = listFiles(currentDir);
                String label = fileSystem.getVolumeLabel();
                long cap = fileSystem.getCapacity();
                long free = fileSystem.getFreeSpace();
                mainHandler.post(() -> {
                    showProgress(false);
                    tvPath.setText("/");
                    setStatus((label != null && !label.isEmpty() ? label : "USB Drive")
                            + "  (" + formatSize(cap) + " total, "
                            + formatSize(free) + " free)");
                    fileAdapter.setFiles(files);
                });
            } catch (Exception e) {
                showError("Failed to read USB drive: " + e.getMessage()
                        + "\n\nCommon causes:\n• Drive is NTFS (not supported – reformat to FAT32)\n• Drive is corrupted – try chkdsk on a PC first\n• OTG adapter is not providing power");
            }
        });
    }

    private List<UsbFile> listFiles(UsbFile dir) throws IOException {
        UsbFile[] files = dir.listFiles();
        if (files == null) return new ArrayList<>();
        List<UsbFile> list = new ArrayList<>(Arrays.asList(files));
        list.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory())
                return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return list;
    }

    private void openFile(UsbFile file) {
        if (file.isDirectory()) {
            dirStack.push(currentDir);
            currentDir = file;
            showProgress(true);
            executor.execute(() -> {
                try {
                    List<UsbFile> files = listFiles(currentDir);
                    String path = file.getName();
                    mainHandler.post(() -> {
                        showProgress(false);
                        tvPath.setText("/" + path);
                        fileAdapter.setFiles(files);
                    });
                } catch (Exception e) {
                    showError("Cannot open folder: " + e.getMessage());
                }
            });
        } else {
            String name = file.getName().toLowerCase();
            if (isImage(name)) {
                showImagePreview(file);
            } else {
                Toast.makeText(this, file.getName() + "\n" + formatSize(file.getLength()),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showImagePreview(UsbFile file) {
        showProgress(true);
        executor.execute(() -> {
            try (InputStream is = new UsbFileInputStream(file)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                mainHandler.post(() -> {
                    showProgress(false);
                    if (bmp == null) {
                        Toast.makeText(this, "Cannot decode image", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    new AlertDialog.Builder(this)
                            .setTitle(file.getName())
                            .setView(iv)
                            .setPositiveButton("Close", null)
                            .show();
                });
            } catch (Exception e) {
                showError("Cannot open image: " + e.getMessage());
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (!dirStack.isEmpty()) {
            currentDir = dirStack.pop();
            showProgress(true);
            executor.execute(() -> {
                try {
                    List<UsbFile> files = listFiles(currentDir);
                    String path = dirStack.isEmpty() ? "/" : currentDir.getName();
                    mainHandler.post(() -> {
                        showProgress(false);
                        tvPath.setText(path);
                        fileAdapter.setFiles(files);
                    });
                } catch (Exception e) {
                    showError("Cannot navigate back: " + e.getMessage());
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        executor.shutdownNow();
        if (storageDevice != null) storageDevice.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private boolean isImage(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") ||
               name.endsWith(".png") || name.endsWith(".gif") ||
               name.endsWith(".bmp") || name.endsWith(".webp") ||
               name.endsWith(".heic") || name.endsWith(".heif");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void setStatus(String msg) {
        mainHandler.post(() -> tvStatus.setText(msg));
    }

    private void showProgress(boolean show) {
        mainHandler.post(() -> {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            recyclerView.setVisibility(show ? View.GONE : View.VISIBLE);
        });
    }

    private void showError(String msg) {
        mainHandler.post(() -> {
            showProgress(false);
            tvStatus.setText(msg);
            tvStatus.setTextColor(0xFFF44336);
            fileAdapter.setFiles(new ArrayList<>());
        });
    }

    // ── File list adapter ─────────────────────────────────────────────────

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        private List<UsbFile> files = new ArrayList<>();

        void setFiles(List<UsbFile> list) {
            files = list;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_usb_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            UsbFile f = files.get(pos);
            h.tvName.setText(f.getName());
            if (f.isDirectory()) {
                h.ivIcon.setImageResource(android.R.drawable.ic_menu_agenda);
                h.tvSize.setText("Folder");
                h.ivThumb.setVisibility(View.GONE);
            } else {
                h.ivIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                h.tvSize.setText(formatSize(f.getLength()));
                if (isImage(f.getName().toLowerCase())) {
                    h.ivThumb.setVisibility(View.VISIBLE);
                    loadThumb(f, h.ivThumb);
                } else {
                    h.ivThumb.setVisibility(View.GONE);
                }
            }
            h.itemView.setOnClickListener(v -> openFile(f));
        }

        private void loadThumb(UsbFile file, ImageView iv) {
            iv.setImageResource(android.R.drawable.ic_menu_gallery);
            executor.execute(() -> {
                try (InputStream is = new UsbFileInputStream(file)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 8;
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    mainHandler.post(() -> {
                        if (bmp != null) iv.setImageBitmap(bmp);
                    });
                } catch (Exception ignored) {}
            });
        }

        @Override
        public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon, ivThumb;
            TextView tvName, tvSize;
            VH(View v) {
                super(v);
                ivIcon  = v.findViewById(R.id.ivIcon);
                ivThumb = v.findViewById(R.id.ivThumb);
                tvName  = v.findViewById(R.id.tvName);
                tvSize  = v.findViewById(R.id.tvSize);
            }
        }
    }
}
