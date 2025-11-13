package mw.hybridcloud;

import java.util.List;

public interface MWCloudPlatform {

	MWVirtualMachine startVM(MWVirtualMachineConfig conf) throws MWCloudException;
	
	void deleteVM(MWVirtualMachine vm_ref) throws MWCloudException;
	
	List<MWVirtualMachine> listVMs() throws MWCloudException;

	Double getCPUUsage(MWVirtualMachine vm, int seconds) throws MWCloudException;

	boolean isInstanceRunning(MWVirtualMachine vm) throws MWCloudException;
}
