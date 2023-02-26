package de.yaacc.browser;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import org.fourthline.cling.model.meta.Device;

import java.util.List;

public class DeviceDiffCallback extends DiffUtil.Callback {

    private final List<Device<?, ?, ?>> oldDeviceList;
    private final List<Device<?, ?, ?>> newDeviceList;

    public DeviceDiffCallback(List<Device<?, ?, ?>> oldDeviceList, List<Device<?, ?, ?>> newDeviceList) {
        this.oldDeviceList = oldDeviceList;
        this.newDeviceList = newDeviceList;
    }

    @Override
    public int getOldListSize() {
        return oldDeviceList.size();
    }

    @Override
    public int getNewListSize() {
        return newDeviceList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        return oldDeviceList.get(oldItemPosition).getIdentity().getUdn() == newDeviceList.get(
                newItemPosition).getIdentity().getUdn();
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        final Device<?, ?, ?> oldDevice = oldDeviceList.get(oldItemPosition);
        final Device<?, ?, ?> newDevice = newDeviceList.get(newItemPosition);
        return oldDevice.getIdentity().getUdn().equals(newDevice.getIdentity().getUdn());
    }

    @Nullable
    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        // Implement method if you're going to use ItemAnimator
        return super.getChangePayload(oldItemPosition, newItemPosition);
    }
}
