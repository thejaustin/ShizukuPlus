package moe.shizuku.manager.management;

import android.content.pm.PackageInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.recyclerview.BaseRecyclerViewAdapter;
import rikka.recyclerview.ClassCreatorPool;

import androidx.recyclerview.widget.DiffUtil;
import java.util.ArrayList;

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
        final List<Object> newList = new ArrayList<>();
        if (data.isEmpty()) {
            newList.add(new Object());
        } else {
            newList.add(new HeaderMarker());
            newList.addAll(data);
        }

        final List<Object> oldList = new ArrayList<>(getItems());
        
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return newList.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                Object oldItem = oldList.get(oldItemPosition);
                Object newItem = newList.get(newItemPosition);
                if (oldItem instanceof PackageInfo && newItem instanceof PackageInfo) {
                    return ((PackageInfo) oldItem).packageName.equals(((PackageInfo) newItem).packageName);
                }
                return oldItem.getClass().equals(newItem.getClass());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return areItemsTheSame(oldItemPosition, newItemPosition);
            }
        });

        getItems().clear();
        getItems().addAll(newList);
        diffResult.dispatchUpdatesTo(this);
    }
}
