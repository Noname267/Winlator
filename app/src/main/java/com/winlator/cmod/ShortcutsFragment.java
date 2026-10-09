package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.GridLayoutManager;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ShortcutsFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private SharedPreferences preferences;
    private boolean isGridView = true;
    private DividerItemDecoration dividerItemDecoration;
    private Shortcut shortcutForIconUpdate;
    private ActivityResultLauncher<String> iconPickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && shortcutForIconUpdate != null) updateShortcutIcon(uri, shortcutForIconUpdate);
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadShortcutsList();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.shortcuts);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {



        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.shortcuts_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        isGridView = preferences.getBoolean("shortcuts_grid_view", true);
        updateLayoutManager();
        return frameLayout;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        MenuItem item = menu.add(0, 1, 0, isGridView ? "List View" : "Grid View");
        item.setIcon(isGridView ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_menu_gallery);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            isGridView = !isGridView;
            preferences.edit().putBoolean("shortcuts_grid_view", isGridView).apply();
            updateLayoutManager();
            requireActivity().invalidateOptionsMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (recyclerView != null) updateLayoutManager();
    }

    private void updateLayoutManager() {
        if (isGridView) {
            boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            recyclerView.setLayoutManager(new GridLayoutManager(getContext(), isLandscape ? 5 : 2));
            if (dividerItemDecoration != null) {
                recyclerView.removeItemDecoration(dividerItemDecoration);
                dividerItemDecoration = null;
            }
        }
        else {
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            if (dividerItemDecoration == null) {
                dividerItemDecoration = new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL);
                recyclerView.addItemDecoration(dividerItemDecoration);
            }
        }
        // re-set the adapter so every item is inflated with the layout of the current view mode
        if (recyclerView.getAdapter() != null) recyclerView.setAdapter(recyclerView.getAdapter());
    }

    public void loadShortcutsList() {
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts();

        // Validate and remove corrupted shortcuts
        shortcuts.removeIf(shortcut -> shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty());

        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
        if (shortcuts.isEmpty()) emptyTextView.setVisibility(View.VISIBLE);
        else emptyTextView.setVisibility(View.GONE); // Ensure the empty text view is hidden if there are shortcuts
    }


    // Custom icon: the picked image is saved as the shortcut's own icon file (Icon= entry of the .desktop),
    // inside the container icons dir that Shortcut already reads, so it shows up everywhere (list, grid,
    // BigPicture, pinned home-screen shortcut) without any change to the Shortcut class.
    private String ensureIconKey(Shortcut shortcut) {
        ArrayList<String> lines = FileUtils.readLines(shortcut.file);
        String section = "";
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("[")) { section = t; continue; }
            if (section.equals("[Desktop Entry]") && t.startsWith("Icon=") && t.length() > 5) return t.substring(5).trim();
        }

        String iconName = FileUtils.getBasename(shortcut.file.getPath());
        StringBuilder sb = new StringBuilder();
        boolean inEntry = false, added = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("[")) {
                if (inEntry && !added) { sb.append("Icon=").append(iconName).append("\n"); added = true; }
                inEntry = t.equals("[Desktop Entry]");
            }
            if (inEntry && t.startsWith("Icon=")) continue; // drop an empty Icon= line
            sb.append(line).append("\n");
        }
        if (inEntry && !added) sb.append("Icon=").append(iconName).append("\n");
        FileUtils.writeString(shortcut.file, sb.toString());
        return iconName;
    }

    private Bitmap decodeSampledBitmap(Uri uri, int maxSize) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, options);
        }
        int sample = 1;
        while (options.outWidth / (sample * 2) >= maxSize && options.outHeight / (sample * 2) >= maxSize) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, options);
        }
    }

    private void updateShortcutIcon(Uri sourceUri, Shortcut shortcut) {
        try {
            Bitmap source = decodeSampledBitmap(sourceUri, 900);
            if (source == null) throw new IOException("Unsupported image");

            int size = Math.min(source.getWidth(), source.getHeight());
            Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - size) / 2, (source.getHeight() - size) / 2, size, size);
            Bitmap iconBitmap = Bitmap.createScaledBitmap(square, 256, 256, true);

            String iconName = ensureIconKey(shortcut);
            File iconDir = shortcut.container.getIconsDir(64); // searched first by Shortcut
            if (!iconDir.exists()) iconDir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(new File(iconDir, iconName + ".png"))) {
                iconBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            // The whole picture is also saved as the shortcut's cover art, so the grid tile shows it full-size
            // (the square icon above is still used by the list view and pinned home-screen shortcuts).
            Bitmap poster = source;
            int longSide = Math.max(source.getWidth(), source.getHeight());
            if (longSide > 1200) {
                float scale = 1200f / longSide;
                poster = Bitmap.createScaledBitmap(source, Math.round(source.getWidth() * scale), Math.round(source.getHeight() * scale), true);
            }
            shortcut.saveCustomCoverArt(poster);

            String uuid = shortcut.getExtra("uuid");
            if (!uuid.isEmpty()) {
                updateShortcutOnScreen(shortcut.name, shortcut.name, shortcut.container.id,
                        shortcut.file.getPath(), Icon.createWithBitmap(iconBitmap), uuid);
            }

            loadShortcutsList();
            Toast.makeText(getContext(), "Icon updated!", Toast.LENGTH_SHORT).show();
        }
        catch (Exception e) {
            Toast.makeText(getContext(), "Error saving icon", Toast.LENGTH_SHORT).show();
        }
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final View menuButton; // null in the grid layout
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;
            private final View innerArea;
            private final ImageView.ScaleType defaultScaleType;
            private final int[] defaultPadding;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
                this.innerArea = view.findViewById(R.id.LLInnerArea);
                this.defaultScaleType = imageView.getScaleType();
                this.defaultPadding = new int[]{imageView.getPaddingLeft(), imageView.getPaddingTop(), imageView.getPaddingRight(), imageView.getPaddingBottom()};
            }
        }

        @Override
        public int getItemViewType(int position) {
            return isGridView ? 1 : 0;
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = viewType == 1 ? R.layout.shortcut_grid_item : R.layout.shortcut_list_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            if (holder.menuButton != null) holder.menuButton.setOnClickListener(null);
            holder.innerArea.setOnClickListener(null);
            holder.innerArea.setOnLongClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);
            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());
            holder.innerArea.setOnClickListener((v) -> runFromShortcut(item));

            if (isGridView) {
                // long press opens the same menu as the list's menu button
                holder.innerArea.setOnLongClickListener((v) -> {
                    showListItemMenu(holder.innerArea, item);
                    return true;
                });

                Bitmap cover = item.getCoverArt();
                if (cover != null) {
                    holder.imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    holder.imageView.setPadding(0, 0, 0, 0);
                    holder.imageView.setImageBitmap(cover);
                }
                else {
                    int padding = (int)(28 * holder.imageView.getResources().getDisplayMetrics().density);
                    holder.imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    holder.imageView.setPadding(padding, padding, padding, padding);
                    if (item.icon != null) holder.imageView.setImageBitmap(item.icon);
                    else holder.imageView.setImageResource(R.drawable.icon_wine);
                }
            }
            else {
                holder.innerArea.setOnLongClickListener(null);
                holder.imageView.setScaleType(holder.defaultScaleType);
                holder.imageView.setPadding(holder.defaultPadding[0], holder.defaultPadding[1], holder.defaultPadding[2], holder.defaultPadding[3]);
                if (item.icon != null) holder.imageView.setImageBitmap(item.icon);
                else holder.imageView.setImageResource(R.drawable.icon_wine);
                holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
            }
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_change_icon) {
                    shortcutForIconUpdate = shortcut;
                    iconPickerLauncher.launch("image/*");
                }
                else if (itemId == R.id.shortcut_remove) {
                    ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_shortcut, () -> {
                        boolean fileDeleted = shortcut.file.delete();
                        File lnkFile = new File(shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf(".")) + ".lnk");
                        if (lnkFile.exists()) {
                            lnkFile.delete();
                        }

                        if (fileDeleted) {
                            disableShortcutOnScreen(requireContext(), shortcut);
                            loadShortcutsList();
                            Toast.makeText(context, "Shortcut removed successfully.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Failed to remove the shortcut. Please try again.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                else if (itemId == R.id.shortcut_clone_to_container) {
                    // Use the ContainerManager to get the list of containers
                    ContainerManager containerManager = new ContainerManager(context);
                    ArrayList<Container> containers = containerManager.getContainers();

                    // Show a container selection dialog
                    showContainerSelectionDialog(containers, new OnContainerSelectedListener() {
                        @Override
                        public void onContainerSelected(Container selectedContainer) {
                            // Use the selected container to clone the shortcut
                            if (shortcut.cloneToContainer(selectedContainer)) {
                                Toast.makeText(context, "Shortcut cloned successfully.", Toast.LENGTH_SHORT).show();
                                loadShortcutsList(); // Reload the shortcuts to show the cloned one
                            } else {
                                Toast.makeText(context, "Failed to clone shortcut.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
                else if (itemId == R.id.shortcut_add_to_home_screen) {
                    if (shortcut.getExtra("uuid").equals(""))
                        shortcut.genUUID();
                    addShortcutToScreen(shortcut);
                }
                else if (itemId == R.id.shortcut_export) {
                    exportShortcut(shortcut);
                }
                else if (itemId == R.id.shortcut_properties) {
                    showShortcutProperties(shortcut);
                }
                return true;
            });
            listItemMenu.show();
        }


        // Define the listener interface for selecting a container
        public interface OnContainerSelectedListener {
            void onContainerSelected(Container container);
        }

        private void showContainerSelectionDialog(ArrayList<Container> containers, OnContainerSelectedListener listener) {
            // Create an AlertDialog to show the list of containers
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Select a container");

            // Create an array of container names to display
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) {
                containerNames[i] = containers.get(i).getName();
            }

            // Set up the list in the dialog
            builder.setItems(containerNames, (dialog, which) -> {
                // Call the listener when a container is selected
                listener.onContainerSelected(containers.get(which));
            });

            // Show the dialog
            builder.show();
        }

        private void runFromShortcut(Shortcut shortcut) {
            Activity activity = getActivity();

            if (!XrActivity.isEnabled(getContext())) {
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                intent.putExtra("shortcut_name", shortcut.name); // Add this line to pass the shortcut name
                // Check if the shortcut has the disableXinput value; if not, default to false.
                String disableXinputValue = shortcut.getExtra("disableXinput", "0"); // Get value from shortcut or use "0" (false) by default
                intent.putExtra("disableXinput", disableXinputValue); // Use the actual value from the shortcut
                activity.startActivity(intent);
            }
            else XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }

        private void exportShortcut(Shortcut shortcut) {
            // Check for a custom frontend export path in shared preferences
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            String uriString = sharedPreferences.getString("shortcuts_export_path_uri", null);

            File shortcutsDir;

            if (uriString != null) {
                // If custom URI is set, use it
                Uri folderUri = Uri.parse(uriString);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(getContext(), folderUri);

                if (pickedDir == null || !pickedDir.canWrite()) {
                    Toast.makeText(getContext(), "Cannot write to the selected folder", Toast.LENGTH_SHORT).show();
                    return;
                }

                shortcutsDir = new File(FileUtils.getFilePathFromUri(getContext(), folderUri));
            } else {
                shortcutsDir = new File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH);
            }

            if (!shortcutsDir.exists() && !shortcutsDir.mkdirs()) {
                Toast.makeText(getContext(), "Failed to create default directory", Toast.LENGTH_SHORT).show();
                return;
            }

            File exportFile = new File(shortcutsDir, shortcut.file.getName());

            boolean fileExists = exportFile.exists();
            boolean containerIdFound = false;

            try {
                List<String> lines = new ArrayList<>();

                try (BufferedReader reader = new BufferedReader(new FileReader(shortcut.file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("container_id:")) {
                            lines.add("container_id:" + shortcut.container.id);
                            containerIdFound = true;
                        } else {
                            lines.add(line);
                        }
                    }
                }

                if (!containerIdFound) {
                    lines.add("container_id:" + shortcut.container.id);
                }

                try (FileWriter writer = new FileWriter(exportFile, false)) {
                    for (String line : lines) {
                        writer.write(line + "\n");
                    }
                    writer.flush();
                }

                Log.d("ShortcutsFragment", "Shortcut exported successfully to " + exportFile.getPath());

                // Determine the toast message
                String message;
                if (fileExists) {
                    message = "Shortcut Updated at " + exportFile.getPath();
                } else {
                    message = "Shortcut Exported to " + exportFile.getPath();
                }

                // Show a toast message to the user
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to export shortcut", e);
                Toast.makeText(getContext(), "Failed to export shortcut", Toast.LENGTH_LONG).show();
            }
        }

        private void showShortcutProperties(Shortcut shortcut) {
            SharedPreferences playtimePrefs = getContext().getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);

            String playtimeKey = shortcut.name + "_playtime";
            String playCountKey = shortcut.name + "_play_count";

            long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0);
            int playCount = playtimePrefs.getInt(playCountKey, 0);

            // Convert playtime to human-readable format
            long seconds = (totalPlaytime / 1000) % 60;
            long minutes = (totalPlaytime / (1000 * 60)) % 60;
            long hours = (totalPlaytime / (1000 * 60 * 60)) % 24;
            long days = (totalPlaytime / (1000 * 60 * 60 * 24));

            String playtimeFormatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);

            // Create the properties dialog
            ContentDialog dialog = new ContentDialog(getContext(), R.layout.shortcut_properties_dialog);
            dialog.setTitle("Properties");

            TextView playCountTextView = dialog.findViewById(R.id.play_count);
            TextView playtimeTextView = dialog.findViewById(R.id.playtime);

            playCountTextView.setText("Number of times played: " + playCount);
            playtimeTextView.setText("Playtime: " + playtimeFormatted);

            Button resetPropertiesButton = dialog.findViewById(R.id.reset_properties);

            resetPropertiesButton.setOnClickListener(v -> {
                playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply();
                Toast.makeText(getContext(), "Properties reset successfully.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();
        }




    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);

        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported())
            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid")), null);
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        try {
            for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
                if (shortcutInfo.getId().equals(uuid)) {
                    shortcutManager.updateShortcuts(Collections.singletonList(
                            buildScreenShortCut(shortLabel, longLabel, containerId, shortcutPath, icon, uuid)));
                    break;
                }
            }
        } catch (Exception e) {}
    }
}
