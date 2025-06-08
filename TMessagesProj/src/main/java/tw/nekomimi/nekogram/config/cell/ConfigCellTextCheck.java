package tw.nekomimi.nekogram.config.cell;

import static org.telegram.messenger.LocaleController.getString;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.Cells.TextCheckCell;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;

public class ConfigCellTextCheck extends AbstractConfigCell {
    private final ConfigItem bindConfig;
    private final CharSequence title;
    private final String subtitle;
    private boolean enabled = true;
    public TextCheckCell cell;

    public ConfigCellTextCheck(ConfigItem bind) {
        this(bind, null);
    }

    public ConfigCellTextCheck(ConfigItem bind, String subtitle) {
        this(bind, subtitle, null);
    }

    public ConfigCellTextCheck(ConfigItem bind, String subtitle, CharSequence customTitle) {
        this.bindConfig = bind;
        this.title = customTitle == null ? getString(bindConfig.getKey()) : customTitle;
        this.subtitle = subtitle;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_TEXT_CHECK;
    }

    public ConfigItem getBindConfig() {
        return bindConfig;
    }

    public CharSequence getTitle() {
        return title;
    }

    public String getKey() {
        return bindConfig == null ? null : bindConfig.getKey();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (this.cell != null) {
            this.cell.setEnabled(this.enabled);
        }
    }

    public void setEnabledAndUpdateState(boolean enabled) {
        this.enabled = enabled;
        if (this.cell != null) {
            this.cell.setEnabled(this.enabled);
            if (subtitle == null) {
                cell.setTextAndCheck(title, bindConfig.Bool(), cellGroup.needSetDivider(this));
            } else {
                cell.setTextAndValueAndCheck(title.toString(), subtitle, bindConfig.Bool(), true, cellGroup.needSetDivider(this));
            }
        }
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        TextCheckCell cell = (TextCheckCell) holder.itemView;
        this.cell = cell;
        if (subtitle == null) {
            cell.setTextAndCheck(title, bindConfig.Bool(), cellGroup.needSetDivider(this));
        } else {
            cell.setTextAndValueAndCheck(title.toString(), subtitle, bindConfig.Bool(), true, cellGroup.needSetDivider(this));
        }
        cell.setEnabled(enabled, null);
    }

    public void onClick(TextCheckCell cell) {
        if (!enabled) return;

        boolean newV = bindConfig.toggleConfigBool();
        cell.setChecked(newV);

        cellGroup.runCallback(bindConfig.getKey(), newV);
    }
}

