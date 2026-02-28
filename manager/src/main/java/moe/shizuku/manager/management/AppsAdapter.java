package moe.shizuku.manager.management;

import android.content.pm.PackageInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.recyclerview.BaseRecyclerViewAdapter;
import rikka.recyclerview.ClassCreatorPool;

public class AppsAdapter extends BaseRecyclerViewAdapter<ClassCreatorPool> {

    public static final class HeaderMarker {}

    private boolean selectionMode = false;
    private final Set<String> selectedPackages = new HashSet<>();

    public AppsAdapter() {
        super();

        getCreatorPool().putRule(HeaderMarker.class, ToggleAllViewHolder.CREATOR);
        getCreatorPool().putRule(PackageInfo.class, AppViewHolder.CREATOR);
        getCreatorPool().putRule(Object.class, EmptyViewHolder.CREATOR);
        setHasStableIds(true);
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selectedPackages.clear();
        }
        notifyDataSetChanged();
    }

    public Set<String> getSelectedPackages() {
        return selectedPackages;
    }

    public void toggleSelection(String packageName) {
        if (selectedPackages.contains(packageName)) {
            selectedPackages.remove(packageName);
        } else {
            selectedPackages.add(packageName);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        Object item = getItemAt(position);
        if (item instanceof PackageInfo) {
            return ((PackageInfo) item).packageName.hashCode();
        }
        return item.hashCode();
    }

    @Override
    public ClassCreatorPool onCreateCreatorPool() {
        return new ClassCreatorPool();
    }

    public void updateData(List<PackageInfo> data) {
        getItems().clear();
        if (data.isEmpty()) {
            getItems().add(new Object());
        } else {
            getItems().add(new HeaderMarker());
            getItems().addAll(data);
        }
        notifyDataSetChanged();
    }
}
