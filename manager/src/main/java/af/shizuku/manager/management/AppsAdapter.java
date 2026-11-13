package af.shizuku.manager.management;

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

    public interface SelectionListener {
        void onSelectionChanged(boolean inSelectionMode, int selectedCount);
    }

    private boolean selectionMode = false;
    private final Set<String> selectedPackages = new HashSet<>();
    private SelectionListener selectionListener;

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    private void notifySelectionListener() {
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectionMode, selectedPackages.size());
        }
    }

    public AppsAdapter() {
        super();

        getCreatorPool().putRule(HeaderMarker.class, ToggleAllViewHolder.CREATOR);
        getCreatorPool().putRule(AppSection.class, SectionHeaderViewHolder.CREATOR);
        getCreatorPool().putRule(PackageInfo.class, AppViewHolder.CREATOR);
        getCreatorPool().putRule(Object.class, EmptyViewHolder.CREATOR);
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
        notifySelectionListener();
    }

    public Set<String> getSelectedPackages() {
        return selectedPackages;
    }

    public void selectAll(java.util.Collection<String> packageNames) {
        selectedPackages.addAll(packageNames);
        notifyDataSetChanged();
        notifySelectionListener();
    }

    public void toggleSelection(String packageName) {
        if (selectedPackages.contains(packageName)) {
            selectedPackages.remove(packageName);
        } else {
            selectedPackages.add(packageName);
        }
        notifyDataSetChanged();
        notifySelectionListener();
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
            if (data instanceof GroupedApps) {
                // All apps: an "Allowed" and a "Not allowed" group, each labelled with its count.
                int allowed = ((GroupedApps) data).getAllowedCount();
                int others = data.size() - allowed;
                if (allowed > 0) {
                    newList.add(new AppSection(true, allowed));
                    newList.addAll(data.subList(0, allowed));
                }
                if (others > 0) {
                    newList.add(new AppSection(false, others));
                    newList.addAll(data.subList(allowed, data.size()));
                }
            } else {
                newList.addAll(data);
            }
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
                if (oldItem instanceof AppSection && newItem instanceof AppSection) {
                    return ((AppSection) oldItem).getAllowed() == ((AppSection) newItem).getAllowed();
                }
                return oldItem.getClass().equals(newItem.getClass());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                // Switch state isn't derived from PackageInfo at all - it's
                // loaded asynchronously in AppViewHolder.onBind() and re-pushed via an explicit
                // notifyItemChanged() right after any grant/revoke/freeze change (see
                // AppViewHolder.onClick/onLongClick). Forcing this false unconditionally used to
                // rebind (and restart icon + granted-state binder lookups for) every visible row
                // on every updateData() call - including ones fired by search/filter keystrokes
                // where most rows' underlying PackageInfo hadn't changed at all.
                Object oldItem = oldList.get(oldItemPosition);
                Object newItem = newList.get(newItemPosition);
                if (oldItem instanceof AppSection && newItem instanceof AppSection) {
                    // Same group, new count: rebind so the label shows it.
                    return oldItem.equals(newItem);
                }
                return areItemsTheSame(oldItemPosition, newItemPosition);
            }
        });

        getItems().clear();
        getItems().addAll(newList);
        diffResult.dispatchUpdatesTo(this);
    }
}
