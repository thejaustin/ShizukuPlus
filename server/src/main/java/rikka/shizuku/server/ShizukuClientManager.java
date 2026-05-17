package rikka.shizuku.server;

import java.util.Iterator;
import java.util.List;

public class ShizukuClientManager extends ClientManager<ShizukuConfigManager> {

    public ShizukuClientManager(ShizukuConfigManager configManager) {
        super(configManager);
    }

    public void removeClientsForPackage(String packageName) {
        List<ClientRecord> records = getClientRecords();
        synchronized (records) {
            Iterator<ClientRecord> iterator = records.iterator();
            while (iterator.hasNext()) {
                ClientRecord record = iterator.next();
                if (packageName.equals(record.packageName)) {
                    iterator.remove();
                }
            }
        }
    }
}
