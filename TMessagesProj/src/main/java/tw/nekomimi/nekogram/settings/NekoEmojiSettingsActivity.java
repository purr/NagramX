package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CreationTextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.ChatAttachAlertDocumentLayout;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.remote.EmojiHelper;
import tw.nekomimi.nekogram.ui.cells.EmojiSetCell;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class NekoEmojiSettingsActivity extends BaseNekoSettingsActivity implements EmojiHelper.EmojiPackLoadListener, EmojiHelper.EmojiPacksLoadedListener, ChatAttachAlertDocumentLayout.DocumentSelectActivityDelegate {

    private static final int menu_delete = 0;
    private static final int menu_share = 1;

    private final ArrayList<EmojiHelper.EmojiPackInfo> emojiPacks = new ArrayList<>();
    private final ArrayList<EmojiHelper.EmojiPackBase> customEmojiPacks = new ArrayList<>();

    private ListAdapter listAdapter;

    private int generalRow;
    private int useSystemEmojiRow;
    private int general2Row;

    private int useCustomEmojiRow;
    private int customEmojiStartRow;
    private int customEmojiEndRow;
    private int customEmojiAddRow;
    private int useSystemEmoji2Row;

    private int emojiPackRow;
    private int emojiPacksStartRow;
    private int emojiPacksEndRow;
    private int placeHolderRow;
    private int emojiPack2Row;

    private ChatAttachAlert chatAttachAlert;
    private NumberTextView selectedCountTextView;
    private AlertDialog progressDialog;

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        ActionBarMenu actionMode = actionBar.createActionMode();

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed()) {
                        finishFragment();
                    }
                } else if (id == menu_delete || id == menu_share) {
                    listAdapter.processSelectionMenu(id);
                }
            }
        });
        selectedCountTextView = new NumberTextView(actionMode.getContext());
        selectedCountTextView.setTextSize(18);
        selectedCountTextView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        selectedCountTextView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountTextView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        actionMode.addItemWithWidth(menu_share, R.drawable.msg_share, AndroidUtilities.dp(54));
        actionMode.addItemWithWidth(menu_delete, R.drawable.msg_delete, AndroidUtilities.dp(54));
        return view;
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position >= customEmojiStartRow && position < customEmojiEndRow) {
            listAdapter.toggleSelected(position);
            return true;
        }
        return super.onItemLongClick(view, position, x, y);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position >= emojiPacksStartRow && position < emojiPacksEndRow) {
            EmojiSetCell cell = (EmojiSetCell) view;
            if (cell.isChecked() || listAdapter.hasSelected()) return;
            EmojiHelper.EmojiPackInfo pack = (EmojiHelper.EmojiPackInfo) cell.getPack();
            boolean downloading = EmojiHelper.getInstance().isEmojiPackDownloading(pack);
            boolean downloaded = EmojiHelper.getInstance().isPackDownloaded(pack);
            boolean installed = EmojiHelper.getInstance().isPackInstalled(pack);
            if (downloading || (downloaded && !installed)) return;

            if (installed || pack.getPackId().equals("default")) {
                EmojiHelper.getInstance().setEmojiPack(pack.getPackId());
                cell.setChecked(true, true);
                EmojiHelper.reloadEmoji();
                listAdapter.notifyItemChanged(useSystemEmojiRow, PARTIAL);
            } else {
                cell.setProgress(true, true);
                EmojiHelper.getInstance().downloadPack(pack, false, false);
            }
            listAdapter.notifyEmojiSetsChanged();
        } else if (position == useSystemEmojiRow) {
            NekoConfig.useSystemEmoji.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useSystemEmoji.Bool());
            }
            EmojiHelper.reloadEmoji();
            listAdapter.notifyEmojiSetsChanged();
        } else if (position == customEmojiAddRow) {
            chatAttachAlert = new ChatAttachAlert(getParentActivity(), NekoEmojiSettingsActivity.this, false, false);
            chatAttachAlert.setEmojiPicker();
            chatAttachAlert.init();
            chatAttachAlert.show();
        } else if (position >= customEmojiStartRow && position < customEmojiEndRow) {
            EmojiSetCell cell = (EmojiSetCell) view;
            if (listAdapter.hasSelected()) {
                listAdapter.toggleSelected(position);
            } else {
                if (cell.isChecked()) return;
                cell.setChecked(true, true);
                listAdapter.notifyEmojiSetsChanged();
                EmojiHelper.getInstance().setEmojiPack(cell.getPack().getPackId());
                EmojiHelper.reloadEmoji();
                listAdapter.notifyItemChanged(useSystemEmojiRow, PARTIAL);
            }
        }
    }

    @Override
    protected String getKey() {
        return "emoji";
    }

    @Override
    public boolean onFragmentCreate() {
        EmojiHelper.getInstance().loadEmojisInfo(this);
        EmojiHelper.getInstance().addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        EmojiHelper.getInstance().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.EmojiSets);
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        updatePacks();

        generalRow = addRow();
        useSystemEmojiRow = addRow("useSystemEmoji");
        general2Row = addRow();

        useCustomEmojiRow = addRow();
        customEmojiStartRow = rowCount;
        rowCount += customEmojiPacks.size();
        customEmojiEndRow = rowCount;
        customEmojiAddRow = addRow("customEmojiAdd");
        useSystemEmoji2Row = addRow();

        emojiPackRow = addRow();
        if (!emojiPacks.isEmpty()) {
            emojiPacksStartRow = rowCount;
            for (var pack : emojiPacks) {
                addRow(pack.getPackId());
            }
            emojiPacksEndRow = rowCount;
            placeHolderRow = -1;
        } else {
            emojiPacksStartRow = -1;
            emojiPacksEndRow = -1;
            placeHolderRow = addRow();
        }
        emojiPack2Row = addRow();
    }

    public void updatePacks() {
        emojiPacks.clear();
        customEmojiPacks.clear();
        emojiPacks.addAll(EmojiHelper.getInstance().getEmojiPacksInfo());
        customEmojiPacks.addAll(EmojiHelper.getInstance().getEmojiCustomPacksInfo());
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return listAdapter = new ListAdapter(context);
    }

    @Override
    public void didSelectFiles(ArrayList<String> files, String caption, ArrayList<MessageObject> fMessages, boolean notify, int scheduleDate, long effectId, boolean invertMedia, long payStars) {
        ArrayList<File> filesToUpload = new ArrayList<>();
        for (String file : files) {
            File f = new File(file);
            if (f.exists()) {
                filesToUpload.add(f);
            }
        }
        processFiles(filesToUpload);
    }

    @Override
    public void emojiPacksLoaded(String error) {
        if (error != null) {
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this).createErrorBulletin(error);
            }
        } else {
            updateListAnimated();
        }
    }

    @Override
    public void progressChanged(EmojiHelper.EmojiPackInfo pack, boolean finished, float progress, long bytesLoaded) {
        if (listView == null || listAdapter == null) {
            return;
        }
        EmojiSetCell cell = null;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View view = listView.getChildAt(i);
            if (view instanceof EmojiSetCell) {
                if (((EmojiSetCell) view).getPack().getPackId().equals(pack.getPackId())) {
                    cell = (EmojiSetCell) view;
                    break;
                }
            }
        }
        if (cell == null) {
            return;
        }

        if (!finished) {
            if (progress == 100f) {
                cell.checkDownloaded(true);
            } else {
                cell.setProgress(progress, bytesLoaded, true);
            }
        } else {
            listAdapter.notifyEmojiSetsChanged();
            listAdapter.notifyItemChanged(useSystemEmojiRow, PARTIAL);
            cell.checkDownloaded(true);
        }
    }

    @Override
    public void startDocumentSelectActivity() {
        try {
            Intent photoPickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
            photoPickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            photoPickerIntent.setType("font/*");
            startActivityForResult(photoPickerIntent, 21);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private File copyFileToCache(Uri uri) {
        try (InputStream is = getParentActivity().getContentResolver().openInputStream(uri)) {
            String fileName = MediaController.getFileName(uri);
            File sharingDirectory = AndroidUtilities.getSharingDirectory();
            if (!sharingDirectory.exists() && !sharingDirectory.mkdirs()) {
                return null;
            }
            File dest = new File(sharingDirectory, fileName == null ? "Emoji.ttf" : fileName);
            AndroidUtilities.copyFile(is, dest);
            return dest;
        } catch (IOException e) {
            FileLog.e(e);
        }
        return null;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == 21) {
            if (data == null) {
                return;
            }

            if (chatAttachAlert != null) {
                progressDialog = new AlertDialog(getParentActivity(), 3);
                progressDialog.setCanCancel(false);
                progressDialog.showDelayed(300);

                Utilities.globalQueue.postRunnable(() -> {
                    if (chatAttachAlert == null || progressDialog == null) {
                        return;
                    }
                    ArrayList<File> files = new ArrayList<>();
                    if (data.getData() != null) {
                        File file = copyFileToCache(data.getData());
                        if (chatAttachAlert.getDocumentLayout().isEmojiFont(file)) {
                            files.add(file);
                        }
                    } else if (data.getClipData() != null) {
                        ClipData clipData = data.getClipData();
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            File file = copyFileToCache(clipData.getItemAt(i).getUri());
                            if (chatAttachAlert.getDocumentLayout().isEmojiFont(file)) {
                                files.add(file);
                            } else {
                                files.clear();
                                break;
                            }
                        }
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!files.isEmpty()) {
                            chatAttachAlert.dismiss();
                            processFiles(files);
                        } else {
                            progressDialog.dismiss();
                            progressDialog = null;
                        }
                    });
                });
            }
        }
    }

    public void processFiles(ArrayList<File> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (progressDialog == null) {
            progressDialog = new AlertDialog(getParentActivity(), 3);
            progressDialog.setCanCancel(false);
            progressDialog.showDelayed(300);
        }
        Utilities.globalQueue.postRunnable(() -> {
            int count = 0;
            for (File file : files) {
                try {
                    if (EmojiHelper.getInstance().installEmoji(file) != null) {
                        count++;
                    }
                } catch (Exception e) {
                    FileLog.e("Emoji Font install failed", e);
                }
            }
            int finalCount = count;
            AndroidUtilities.runOnUIThread(() -> {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                    progressDialog = null;
                }
                listAdapter.notifyItemRangeInserted(customEmojiEndRow, finalCount);
                updateRows();
            });
        });
    }

    @Override
    public boolean onBackPressed() {
        if (listAdapter.hasSelected()) {
            listAdapter.clearSelected();
            return false;
        }
        return super.onBackPressed();
    }

    private class ListAdapter extends BaseListAdapter {
        private final SparseBooleanArray selectedItems = new SparseBooleanArray();

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_CHECK: {
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    if (position == useSystemEmojiRow) {
                        if (partial) {
                            textCheckCell.setChecked(NekoConfig.useSystemEmoji.Bool());
                        } else {
                            textCheckCell.setTextAndCheck(getString(R.string.EmojiUseDefault), NekoConfig.useSystemEmoji.Bool(), false);
                        }
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell headerViewHolder = (HeaderCell) holder.itemView;
                    if (position == emojiPackRow) {
                        headerViewHolder.setText(getString(R.string.EmojiSets));
                    } else if (position == generalRow) {
                        headerViewHolder.setText(getString(R.string.General));
                    } else if (position == useCustomEmojiRow) {
                        headerViewHolder.setText(getString(R.string.MyEmojiSets));
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == emojiPack2Row) {
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                        cell.setText(getString(R.string.EmojiSetHint));
                    } else if (position == useSystemEmoji2Row) {
                        cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                        cell.setText(getString(R.string.CustomEmojiSetHint));
                    }
                    break;
                }
                case TYPE_EMOJI_SELECTION: {
                    EmojiSetCell emojiPackSetCell = (EmojiSetCell) holder.itemView;
                    EmojiHelper.EmojiPackBase emojiPackInfo = null;
                    if (position >= emojiPacksStartRow && position < emojiPacksEndRow) {
                        emojiPackInfo = emojiPacks.get(position - emojiPacksStartRow);
                    } else if (position >= customEmojiStartRow && position < customEmojiEndRow) {
                        emojiPackInfo = customEmojiPacks.get(position - customEmojiStartRow);
                    }
                    emojiPackSetCell.setSelected(selectedItems.get(position, false), partial);
                    if (emojiPackInfo != null) {
                        emojiPackSetCell.setChecked(!hasSelected() && emojiPackInfo.getPackId().equals(EmojiHelper.getInstance().getSelectedEmojiPackId()) && !NekoConfig.useSystemEmoji.Bool(), partial);
                        emojiPackSetCell.setData(emojiPackInfo, partial, position != emojiPacksEndRow - 1);
                    }
                    break;
                }
                case TYPE_CREATION: {
                    CreationTextCell creationTextCell = (CreationTextCell) holder.itemView;
                    if (position == customEmojiAddRow) {
                        Drawable drawable1 = creationTextCell.getContext().getResources().getDrawable(R.drawable.poll_add_circle);
                        Drawable drawable2 = creationTextCell.getContext().getResources().getDrawable(R.drawable.poll_add_plus);
                        drawable1.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_switchTrackChecked), PorterDuff.Mode.MULTIPLY));
                        drawable2.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_checkboxCheck), PorterDuff.Mode.MULTIPLY));
                        CombinedDrawable combinedDrawable = new CombinedDrawable(drawable1, drawable2);
                        creationTextCell.setTextAndIcon(getString(R.string.AddEmojiSet), combinedDrawable, false);
                    }
                    break;
                }
                case TYPE_FLICKER: {
                    FlickerLoadingView flickerLoadingView = (FlickerLoadingView) holder.itemView;
                    flickerLoadingView.setViewType(FlickerLoadingView.STICKERS_TYPE);
                    flickerLoadingView.setIsSingleCell(true);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == general2Row) {
                return TYPE_SHADOW;
            } else if (position == useSystemEmojiRow) {
                return TYPE_CHECK;
            } else if (position == emojiPackRow || position == generalRow || position == useCustomEmojiRow) {
                return TYPE_HEADER;
            } else if (position == emojiPack2Row || position == useSystemEmoji2Row) {
                return TYPE_INFO_PRIVACY;
            } else if ((position >= emojiPacksStartRow && position < emojiPacksEndRow) || (position >= customEmojiStartRow && position < customEmojiEndRow)) {
                return TYPE_EMOJI_SELECTION;
            } else if (position == customEmojiAddRow) {
                return TYPE_CREATION;
            } else if (position == placeHolderRow) {
                return TYPE_FLICKER;
            } else {
                return TYPE_TEXT;
            }
        }

        public void toggleSelected(int position) {
            selectedItems.put(position, !selectedItems.get(position, false));
            notifyEmojiSetsChanged();
            checkActionMode();
        }

        public boolean hasSelected() {
            return selectedItems.indexOfValue(true) != -1;
        }

        public void clearSelected() {
            selectedItems.clear();
            notifyEmojiSetsChanged();
            checkActionMode();
        }

        public int getSelectedCount() {
            int count = 0;
            for (int i = 0, size = selectedItems.size(); i < size; i++) {
                if (selectedItems.valueAt(i)) {
                    count++;
                }
            }
            return count;
        }

        private void notifyEmojiSetsChanged() {
            notifyItemRangeChanged(customEmojiStartRow, customEmojiEndRow - customEmojiStartRow, PARTIAL);
            notifyItemRangeChanged(emojiPacksStartRow, emojiPacksEndRow - emojiPacksStartRow, PARTIAL);
        }

        private void checkActionMode() {
            int selectedCount = getSelectedCount();
            boolean actionModeShowed = actionBar.isActionModeShowed();
            if (selectedCount > 0) {
                selectedCountTextView.setNumber(selectedCount, actionModeShowed);
                if (!actionModeShowed) {
                    actionBar.showActionMode();
                }
            } else if (actionModeShowed) {
                actionBar.hideActionMode();
            }
        }

        private void processSelectionMenu(int which) {
            ArrayList<EmojiHelper.EmojiPackBase> stickerSetList = new ArrayList<>(selectedItems.size());
            for (int i = 0, size = customEmojiPacks.size(); i < size; i++) {
                EmojiHelper.EmojiPackBase pack = customEmojiPacks.get(i);
                if (selectedItems.get(customEmojiStartRow + i, false)) {
                    stickerSetList.add(pack);
                }
            }
            int count = stickerSetList.size();
            if (count > 1) {
                if (which == menu_share) {
                    Intent intent = new Intent();
                    intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                    intent.setType("font/ttf");
                    ArrayList<Uri> uriList = new ArrayList<>();
                    for (EmojiHelper.EmojiPackBase packTmp : stickerSetList) {
                        uriList.add(FileProvider.getUriForFile(mContext, ApplicationLoader.getApplicationId() + ".provider", new File(packTmp.getFileLocation())));
                    }
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList);
                    mContext.startActivity(Intent.createChooser(intent, getString(R.string.ShareFile)));
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(LocaleController.formatString(R.string.DeleteStickerSetsAlertTitle, LocaleController.formatString(R.string.DeleteEmojiSets, count)));
                    builder.setMessage(getString(R.string.DeleteEmojiSetsMessage));
                    builder.setPositiveButton(getString(R.string.Delete), (dialog, which1) -> {
                        AlertDialog progressDialog = new AlertDialog(getParentActivity(), 3);
                        Utilities.globalQueue.postRunnable(() -> {
                            for (int i = 0, size = stickerSetList.size(); i < size; i++) {
                                EmojiHelper.getInstance().deleteEmojiPack(stickerSetList.get(i));
                            }
                            AndroidUtilities.runOnUIThread(() -> {
                                progressDialog.dismiss();
                                clearSelected();
                                updateListAnimated();
                                EmojiHelper.reloadEmoji();
                            });
                        });
                        progressDialog.setCanCancel(false);
                        progressDialog.showDelayed(300);
                    });
                    builder.setNegativeButton(getString(R.string.Cancel), null);
                    AlertDialog dialog = builder.create();
                    showDialog(dialog);
                    dialog.redPositive();
                }
            } else {
                EmojiHelper.EmojiPackBase pack = stickerSetList.get(0);
                if (which == menu_share) {
                    Intent intent = new Intent();
                    intent.setAction(Intent.ACTION_SEND);
                    intent.setType("font/ttf");
                    Uri uri = FileProvider.getUriForFile(mContext, ApplicationLoader.getApplicationId() + ".provider", new File(pack.getFileLocation()));
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    mContext.startActivity(Intent.createChooser(intent, getString(R.string.ShareFile)));
                    clearSelected();
                } else {
                    EmojiHelper.getInstance().cancelableDelete(NekoEmojiSettingsActivity.this, pack, new EmojiHelper.OnBulletinAction() {
                        @Override
                        public void onPreStart() {
                            notifyItemRemoved(customEmojiStartRow + customEmojiPacks.indexOf(pack));
                            notifyEmojiSetsChanged();
                            updateRows();
                            clearSelected();
                        }

                        @Override
                        public void onUndo() {
                            notifyItemInserted(customEmojiStartRow + EmojiHelper.getInstance().getEmojiCustomPacksInfo().indexOf(pack));
                            updateRows();
                            notifyEmojiSetsChanged();
                        }
                    });
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateListAnimated() {
        if (listAdapter == null) {
            updateRows();
            return;
        }
        DiffCallback diffCallback = new DiffCallback();
        diffCallback.oldRowCount = rowCount;
        diffCallback.fillPositions(diffCallback.oldPositionToItem);
        diffCallback.oldEmojiPacks.clear();
        diffCallback.oldCustomPacks.clear();
        diffCallback.oldEmojiPacks.addAll(emojiPacks);
        diffCallback.oldCustomPacks.addAll(customEmojiPacks);
        diffCallback.oldEmojiPacksStartRow = emojiPacksStartRow;
        diffCallback.oldEmojiPacksEndRow = emojiPacksEndRow;
        diffCallback.oldCustomEmojiStartRow = customEmojiStartRow;
        diffCallback.oldCustomEmojiEndRow = customEmojiEndRow;
        updateRows();
        diffCallback.fillPositions(diffCallback.newPositionToItem);
        try {
            DiffUtil.calculateDiff(diffCallback).dispatchUpdatesTo(listAdapter);
        } catch (Exception e) {
            listAdapter.notifyDataSetChanged();
        }
        AndroidUtilities.updateVisibleRows(listView);
    }

    private class DiffCallback extends DiffUtil.Callback {

        int oldRowCount;

        SparseIntArray oldPositionToItem = new SparseIntArray();
        SparseIntArray newPositionToItem = new SparseIntArray();
        ArrayList<EmojiHelper.EmojiPackBase> oldEmojiPacks = new ArrayList<>();
        ArrayList<EmojiHelper.EmojiPackBase> oldCustomPacks = new ArrayList<>();
        int oldEmojiPacksStartRow;
        int oldEmojiPacksEndRow;
        int oldCustomEmojiStartRow;
        int oldCustomEmojiEndRow;

        @Override
        public int getOldListSize() {
            return oldRowCount;
        }

        @Override
        public int getNewListSize() {
            return rowCount;
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            if (newItemPosition >= emojiPacksStartRow && newItemPosition < emojiPacksEndRow) {
                if (oldItemPosition >= oldEmojiPacksStartRow && oldItemPosition < oldEmojiPacksEndRow) {
                    EmojiHelper.EmojiPackBase oldItem = oldEmojiPacks.get(oldItemPosition - oldEmojiPacksStartRow);
                    EmojiHelper.EmojiPackBase newItem = emojiPacks.get(newItemPosition - emojiPacksStartRow);
                    return Objects.equals(oldItem.getPackId(), newItem.getPackId());
                }
            }
            if (newItemPosition >= customEmojiStartRow && newItemPosition < customEmojiEndRow) {
                if (oldItemPosition >= oldCustomEmojiStartRow && oldItemPosition < oldCustomEmojiEndRow) {
                    EmojiHelper.EmojiPackBase oldItem = oldCustomPacks.get(oldItemPosition - oldCustomEmojiStartRow);
                    EmojiHelper.EmojiPackBase newItem = customEmojiPacks.get(newItemPosition - customEmojiStartRow);
                    return Objects.equals(oldItem.getPackId(), newItem.getPackId());
                }
            }
            int oldIndex = oldPositionToItem.get(oldItemPosition, -1);
            int newIndex = newPositionToItem.get(newItemPosition, -1);
            return oldIndex == newIndex && oldIndex >= 0;
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return areItemsTheSame(oldItemPosition, newItemPosition);
        }

        public void fillPositions(SparseIntArray sparseIntArray) {
            sparseIntArray.clear();
            int pointer = 0;

            put(++pointer, generalRow, sparseIntArray);
            put(++pointer, useSystemEmojiRow, sparseIntArray);
            put(++pointer, general2Row, sparseIntArray);
            put(++pointer, useCustomEmojiRow, sparseIntArray);
            put(++pointer, customEmojiAddRow, sparseIntArray);
            put(++pointer, useSystemEmoji2Row, sparseIntArray);
            put(++pointer, emojiPackRow, sparseIntArray);
            put(++pointer, placeHolderRow, sparseIntArray);
            put(++pointer, emojiPack2Row, sparseIntArray);
        }

        private void put(int id, int position, SparseIntArray sparseIntArray) {
            if (position >= 0) {
                sparseIntArray.put(position, id);
            }
        }
    }
}
