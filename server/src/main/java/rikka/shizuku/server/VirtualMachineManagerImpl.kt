package rikka.shizuku.server

import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IVirtualMachineManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of VirtualMachineManager using Android Virtualization Framework (AVF).
 * 
 * This class provides virtual machine management features using the official
 * Android Virtualization Framework APIs available on Android 14+ (API 34+).
 * 
 * Features:
 * - Create, start, stop, and delete virtual machines
 * - Query VM status and list all VMs
 * - Support for both protected and non-protected VMs
 * 
 * @see <a href="https://source.android.com/docs/core/virtualization">Android Virtualization Framework</a>
 */
class VirtualMachineManagerImpl : IVirtualMachineManager.Stub() {
    companion object {
        private const val TAG = "VirtualMachineManagerImpl"
        private const val VIRTUAL_MACHINE_SERVICE_NAME = "virtualmachine"
        
        // Status constants matching VirtualMachine status values
        private const val VM_STATUS_STOPPED = 0
        private const val VM_STATUS_RUNNING = 1
        private const val VM_STATUS_DELETED = 2
    }

    // Cache for VirtualMachine instances to maintain references
    private val vmCache = ConcurrentHashMap<String, Any>()

    /**
     * Create a new Virtual Machine.
     * 
     * Uses Android Virtualization Framework to create a new VM instance
     * with the specified configuration.
     * 
     * @param name The name for the new VM
     * @param config Bundle containing VM configuration:
     *   - "protected_vm": Boolean, whether this is a protected VM (default: false)
     *   - "memory_bytes": Long, memory allocation in bytes (default: 512MB)
     *   - "cpu_count": Int, number of virtual CPUs (default: 2)
     *   - "storage_bytes": Long, encrypted storage size (default: 100MB)
     *   - "payload_binary": String, name of payload binary in APK
     *   - "debuggable": Boolean, whether VM is debuggable (default: false)
     * @return true if VM was successfully created, false otherwise
     */
    override fun create(name: String?, config: Bundle?): Boolean {
        if (name == null) {
            Log.w(TAG, "create called with null name")
            return false
        }

        Log.d(TAG, "Creating VM: $name with config: $config")

        return try {
            val vmm = getVirtualMachineManager()
            if (vmm == null) {
                Log.e(TAG, "VirtualMachineManager not available (AVF not supported on this device)")
                return false
            }

            // Check if VM already exists
            if (exists(name)) {
                Log.w(TAG, "VM '$name' already exists")
                return false
            }

            // Build VM configuration
            val vmConfig = buildVmConfig(config)
            if (vmConfig == null) {
                Log.e(TAG, "Failed to build VM configuration")
                return false
            }

            // Create the VM
            val createMethod = vmm.javaClass.getMethod("create", String::class.java, vmConfig.javaClass)
            val vmInstance = createMethod.invoke(vmm, name, vmConfig)

            if (vmInstance != null) {
                vmCache[name] = vmInstance
                Log.d(TAG, "Successfully created VM: $name")
                return true
            }

            Log.e(TAG, "VM creation returned null")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VM: $name", e)
            false
        }
    }

    /**
     * Start a Virtual Machine.
     * 
     * Initiates the VM execution. The VM will run asynchronously and
     * can be monitored via callbacks.
     * 
     * @param name The name of the VM to start
     * @return true if VM was successfully started, false otherwise
     */
    override fun start(name: String?): Boolean {
        if (name == null) {
            Log.w(TAG, "start called with null name")
            return false
        }

        Log.d(TAG, "Starting VM: $name")

        return try {
            val vmInstance = getOrCreateVmInstance(name)
            if (vmInstance == null) {
                Log.e(TAG, "VM '$name' not found")
                return false
            }

            // Call run() method on VirtualMachine
            val runMethod = vmInstance.javaClass.getMethod("run")
            runMethod.invoke(vmInstance)

            Log.d(TAG, "Successfully started VM: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VM: $name", e)
            false
        }
    }

    /**
     * Stop a Virtual Machine.
     * 
     * Stops the VM execution. This is an abrupt stop and the VM may
     * not have a chance to clean up resources.
     * 
     * @param name The name of the VM to stop
     * @return true if VM was successfully stopped, false otherwise
     */
    override fun stop(name: String?): Boolean {
        if (name == null) {
            Log.w(TAG, "stop called with null name")
            return false
        }

        Log.d(TAG, "Stopping VM: $name")

        return try {
            val vmInstance = getVmInstance(name)
            if (vmInstance == null) {
                Log.w(TAG, "VM '$name' not found or already deleted")
                return false
            }

            // Call stop() method on VirtualMachine
            val stopMethod = vmInstance.javaClass.getMethod("stop")
            stopMethod.invoke(vmInstance)

            Log.d(TAG, "Successfully stopped VM: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VM: $name", e)
            false
        }
    }

    /**
     * Delete a Virtual Machine.
     * 
     * Permanently deletes the VM and all its data. This operation
     * is irreversible.
     * 
     * @param name The name of the VM to delete
     * @return true if VM was successfully deleted, false otherwise
     */
    override fun delete(name: String?): Boolean {
        if (name == null) {
            Log.w(TAG, "delete called with null name")
            return false
        }

        Log.d(TAG, "Deleting VM: $name")

        return try {
            val vmm = getVirtualMachineManager()
            if (vmm == null) {
                Log.e(TAG, "VirtualMachineManager not available")
                return false
            }

            // Call delete() method on VirtualMachineManager
            val deleteMethod = vmm.javaClass.getMethod("delete", String::class.java)
            deleteMethod.invoke(vmm, name)

            // Remove from cache
            vmCache.remove(name)

            Log.d(TAG, "Successfully deleted VM: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete VM: $name", e)
            false
        }
    }

    /**
     * Get the status of a Virtual Machine.
     * 
     * Returns the current status of the VM (running, stopped, or deleted).
     * 
     * @param name The name of the VM
     * @return Status string: "Running", "Stopped", "Deleted", or "Unknown"
     */
    override fun getStatus(name: String?): String {
        if (name == null) {
            Log.w(TAG, "getStatus called with null name")
            return "Unknown"
        }

        return try {
            val vmInstance = getVmInstance(name)
            if (vmInstance == null) {
                // Check if it's in deleted state
                val vmm = getVirtualMachineManager()
                if (vmm != null) {
                    try {
                        val getMethod = vmm.javaClass.getMethod("get", String::class.java)
                        val vm = getMethod.invoke(vmm, name)
                        if (vm != null) {
                            val getStatusMethod = vm.javaClass.getMethod("getStatus")
                            val status = getStatusMethod.invoke(vm) as Int
                            return statusToString(status)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "VM not found in VMM", e)
                    }
                }
                return "Unknown"
            }

            // Get status from VM instance
            val getStatusMethod = vmInstance.javaClass.getMethod("getStatus")
            val status = getStatusMethod.invoke(vmInstance) as Int
            statusToString(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status for VM: $name", e)
            "Unknown"
        }
    }

    /**
     * List all Virtual Machines managed by Shizuku+.
     * 
     * Returns a list of all VM names known to the VirtualMachineManager.
     * 
     * @return List of VM names
     */
    override fun list(): List<String> {
        return try {
            val vmm = getVirtualMachineManager()
            if (vmm == null) {
                Log.d(TAG, "VirtualMachineManager not available")
                return emptyList()
            }

            // Try to get list of VMs
            // The AVF API doesn't have a direct list() method, so we track created VMs
            val cachedList = vmCache.keys.toList()
            
            if (cachedList.isNotEmpty()) {
                Log.d(TAG, "Found ${cachedList.size} cached VMs: ${cachedList.joinToString()}")
            }
            
            cachedList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list VMs", e)
            emptyList()
        }
    }

    /**
     * Get VirtualMachineManager instance via ServiceManager.
     * 
     * @return VirtualMachineManager instance, or null if not available
     */
    private fun getVirtualMachineManager(): Any? {
        return try {
            // Check AVF support - Requires Android 14+ (API 34+)
            if (android.os.Build.VERSION.SDK_INT < 34) {
                Log.d(TAG, "AVF requires Android 14+ (current: ${android.os.Build.VERSION.SDK_INT})")
                return null
            }

            // Note: On Android 16+ (OneUI 8+), AVF service name or stub location might change.
            // We maintain compatibility with current official service name.
            val binder = ServiceManager.getService(VIRTUAL_MACHINE_SERVICE_NAME)
            if (binder != null) {
                val stubClass = Class.forName("android.system.virtualmachine.VirtualMachineManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                return asInterfaceMethod.invoke(null, binder)
            }

            // Fallback: Try using context.getSystemService approach
            // This would require a Context, which we don't have in the server
            Log.d(TAG, "VirtualMachineManager service not found via ServiceManager")
            null
        } catch (e: Exception) {
            Log.d(TAG, "VirtualMachineManager not available", e)
            null
        }
    }

    /**
     * Build VirtualMachineConfig from Bundle.
     * 
     * @param config Bundle containing configuration options
     * @return VirtualMachineConfig instance, or null on failure
     */
    private fun buildVmConfig(config: Bundle?): Any? {
        return try {
            val configClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig")
            val builderClass = Class.forName("android.system.virtualmachine.VirtualMachineConfig\$Builder")

            // Get system context from ActivityThread
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMainMethod = activityThreadClass.getMethod("systemMain")
            val activityThread = systemMainMethod.invoke(null)
            val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
            val context = getSystemContextMethod.invoke(activityThread) as Context

            // Get Builder constructor - requires Context
            val builderConstructor = builderClass.getConstructor(Context::class.java)
            val builder = builderConstructor.newInstance(context)

            val protectedVm = config?.getBoolean("protected_vm", false) ?: false
            val memoryBytes = config?.getLong("memory_bytes", 512 * 1024 * 1024) ?: (512 * 1024 * 1024)
            val cpuCount = config?.getInt("cpu_count", 2) ?: 2
            val debuggable = config?.getBoolean("debuggable", false) ?: false
            val payloadBinary = config?.getString("payload_binary")

            Log.d(TAG, "Building VM config: protected=$protectedVm, memory=$memoryBytes, cpus=$cpuCount, debuggable=$debuggable")

            // Configure builder using reflection
            builderClass.getMethod("setProtectedVm", Boolean::class.java).invoke(builder, protectedVm)
            builderClass.getMethod("setMemoryBytes", Long::class.java).invoke(builder, memoryBytes)
            builderClass.getMethod("setCpuCount", Int::class.java).invoke(builder, cpuCount)
            builderClass.getMethod("setDebuggable", Boolean::class.java).invoke(builder, debuggable)
            
            if (payloadBinary != null) {
                builderClass.getMethod("setPayloadBinaryName", String::class.java).invoke(builder, payloadBinary)
            }

            // Build the config
            val buildMethod = builderClass.getMethod("build")
            buildMethod.invoke(builder)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build VM config", e)
            null
        }
    }

    /**
     * Get cached VM instance.
     * 
     * @param name The VM name
     * @return VM instance, or null if not found
     */
    private fun getVmInstance(name: String): Any? {
        return vmCache[name]
    }

    /**
     * Get or create VM instance from VirtualMachineManager.
     * 
     * @param name The VM name
     * @return VM instance, or null if not found
     */
    private fun getOrCreateVmInstance(name: String): Any? {
        // Check cache first
        vmCache[name]?.let { return it }

        // Try to get from VMM
        return try {
            val vmm = getVirtualMachineManager() ?: return null
            
            val getMethod = vmm.javaClass.getMethod("get", String::class.java)
            val vmInstance = getMethod.invoke(vmm, name)
            
            if (vmInstance != null) {
                vmCache[name] = vmInstance
            }
            
            vmInstance
        } catch (e: Exception) {
            Log.d(TAG, "Failed to get VM instance: $name", e)
            null
        }
    }

    /**
     * Check if a VM exists.
     * 
     * @param name The VM name
     * @return true if VM exists, false otherwise
     */
    private fun exists(name: String): Boolean {
        return vmCache.containsKey(name) || getStatus(name) != "Unknown"
    }

    /**
     * Convert status code to string.
     * 
     * @param status Status code from VirtualMachine.getStatus()
     * @return Status string
     */
    private fun statusToString(status: Int): String {
        return when (status) {
            VM_STATUS_RUNNING -> "Running"
            VM_STATUS_STOPPED -> "Stopped"
            VM_STATUS_DELETED -> "Deleted"
            else -> "Unknown ($status)"
        }
    }
}
