package com.thumbviewer;

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

import com.thumbviewer.ntfs.NtfsFile;
import com.thumbviewer.ntfs.NtfsFileSystem;
import com.thumbviewer.ntfs.NtfsFileSystemCreator;

import me.jahnen.libaums.core.UsbMassStorageDevice;
import me.jahnen.libaums.core.fs.FileSystem;
import me.jahnen.libaums.core.fs.FileSystemFactory;
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

public class BrowserActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.thumbviewer.USB_PERMISSION";
    private static boolean ntfsRegistered = false;

    private UsbManager usbManager;
    private UsbMassStorageDevice storageDevice;
    private UsbFile currentDir;
    private final Stack<UsbFile> dirStack = new Stack<>();

    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private TextView tvPath, tvStatus;
    private View progressBar;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (dev != null) mountDevice(dev);
                } else {
                    showError("USB permission denied. Go back and try again.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                showError("USB drive disconnected.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("USB Drive Viewer");
        }

        // Register NTFS support with libaums (once per app session)
        synchronized (BrowserActivity.class) {
            if (!ntfsRegistered) {
                FileSystemFactory.registerFileSystem(new NtfsFileSystemCreator(),
                        FileSystemFactory.DEFAULT_PRIORITY);
                ntfsRegistered = true;
            }
        }

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        tvPath = findViewById(R.id.tvPath);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerView);

        adapter = new FileAdapter();
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setAdapter(adapter);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, filter);

        detectAndMount();
    }

    private void detectAndMount() {
        setStatus("Looking for USB drive…");
        UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
        if (devices.length == 0) {
            showError("No USB drive found.\n\n• Plug in a thumb drive via USB-C OTG adapter\n• The drive must be FAT32, exFAT, or NTFS\n• Try unplugging and re-plugging");
            return;
        }
        UsbDevice usbDev = devices[0].getUsbDevice();
        if (usbManager.hasPermission(usbDev)) {
            mountDevice(usbDev);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(usbDev, pi);
            setStatus("Waiting for USB permission…");
        }
    }

    private void mountDevice(UsbDevice usbDev) {
        setStatus("Reading drive…");
        showProgress(true);
        executor.execute(() -> {
            try {
                UsbMassStorageDevice[] devices = UsbMassStorageDevice.getMassStorageDevices(this);
                UsbMassStorageDevice target = null;
                for (UsbMassStorageDevice d : devices) {
                    if (d.getUsbDevice().getDeviceId() == usbDev.getDeviceId()) {
                        target = d;
                        break;
                    }
                }
                if (target == null) { showError("Drive disappeared."); return; }
                target.init();
                storageDevice = target;

                List<Partition> partitions = target.getPartitions();
                if (partitions.isEmpty()) {
                    showError("No readable partitions found.\n\nSupported: FAT32, exFAT, NTFS");
                    return;
                }

                Partition p = partitions.get(0);
                FileSystem fs = p.getFileSystem();
                currentDir = fs.getRootDirectory();

                String label = fs.getVolumeLabel();
                String fsType = (fs instanceof NtfsFileSystem) ? "NTFS" : "FAT32/exFAT";
                long cap = fs.getCapacity();
                long free = fs.getFreeSpace();

                UsbFile[] files = currentDir.listFiles();
                List<UsbFile> list = sortFiles(files);

                mainHandler.post(() -> {
                    showProgress(false);
                    tvPath.setText("/");
                    setStatus((label != null && !label.isEmpty() ? label : "USB Drive")
                            + "  [" + fsType + "]  "
                            + formatSize(cap) + " / " + formatSize(free) + " free");
                    adapter.setFiles(list);
                });
            } catch (Exception e) {
                showError("Cannot read drive: " + e.getMessage()
                        + "\n\nTry: unplug and re-plug, check OTG adapter");
            }
        });
    }

    private List<UsbFile> sortFiles(UsbFile[] files) {
        List<UsbFile> list = new ArrayList<>(Arrays.asList(files));
        list.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
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
                    List<UsbFile> list = sortFiles(file.listFiles());
                    mainHandler.post(() -> {
                        showProgress(false);
                        tvPath.setText(buildPath(currentDir));
                        adapter.setFiles(list);
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
                Toast.makeText(this, file.getName() + "  —  " + formatSize(file.getLength()),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showImagePreview(UsbFile file) {
        showProgress(true);
        executor.execute(() -> {
            try {
                Bitmap bmp;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                if (file instanceof NtfsFile) {
                    try (InputStream is = ((NtfsFile) file).openStream()) {
                        bmp = BitmapFactory.decodeStream(is, null, opts);
                    }
                } else {
                    try (InputStream is = new UsbFileInputStream(file)) {
                        bmp = BitmapFactory.decodeStream(is, null, opts);
                    }
                }
                final Bitmap finalBmp = bmp;
                mainHandler.post(() -> {
                    showProgress(false);
                    if (finalBmp == null) {
                        Toast.makeText(this, "Cannot decode image", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(finalBmp);
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
                    List<UsbFile> list = sortFiles(currentDir.listFiles());
                    mainHandler.post(() -> {
                        showProgress(false);
                        tvPath.setText(buildPath(currentDir));
                        adapter.setFiles(list);
                    });
                } catch (Exception e) {
                    showError("Navigation error: " + e.getMessage());
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean onSupportNavigateUp() { onBackPressed(); return true; }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        executor.shutdownNow();
        if (storageDevice != null) storageDevice.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String buildPath(UsbFile f) {
        if (f == null || f.isRoot()) return "/";
        try { return f.getAbsolutePath(); } catch (Exception e) { return f.getName(); }
    }

    private boolean isImage(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")
                || name.endsWith(".heic") || name.endsWith(".heif");
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
            adapter.setFiles(new ArrayList<>());
        });
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        private List<UsbFile> files = new ArrayList<>();

        void setFiles(List<UsbFile> list) { files = list; notifyDataSetChanged(); }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file, parent, false);
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
            iv.setTag(file.getName()); // used to avoid stale loads
            iv.setImageResource(android.R.drawable.ic_menu_gallery);
            executor.execute(() -> {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 8;
                    Bitmap bmp;
                    if (file instanceof NtfsFile) {
                        try (InputStream is = ((NtfsFile) file).openStream()) {
                            bmp = BitmapFactory.decodeStream(is, null, opts);
                        }
                    } else {
                        try (InputStream is = new UsbFileInputStream(file)) {
                            bmp = BitmapFactory.decodeStream(is, null, opts);
                        }
                    }
                    final Bitmap finalBmp = bmp;
                    mainHandler.post(() -> {
                        if (file.getName().equals(iv.getTag()) && finalBmp != null)
                            iv.setImageBitmap(finalBmp);
                    });
                } catch (Exception ignored) {}
            });
        }

        @Override public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivIcon, ivThumb;
            TextView tvName, tvSize;
            VH(View v) {
                super(v);
                ivIcon = v.findViewById(R.id.ivIcon);
                ivThumb = v.findViewById(R.id.ivThumb);
                tvName = v.findViewById(R.id.tvName);
                tvSize = v.findViewById(R.id.tvSize);
            }
        }
    }
}
