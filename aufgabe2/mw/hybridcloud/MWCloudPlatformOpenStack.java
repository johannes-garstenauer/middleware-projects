package mw.hybridcloud;

import java.util.List;

public class MWCloudPlatformOpenStack implements MWCloudPlatform {

	/***
	 * debian-example
	 * i4.tiny
	 */


	@Override
	public MWVirtualMachine startVM(MWVirtualMachineConfig conf) throws MWCloudException {
		/*
		 *  TODO: Implement method 
		 */
		return null;
	}

	@Override
	public void deleteVM(MWVirtualMachine vm_ref) throws MWCloudException {
		/*
		 *  TODO: Implement method 
		 */
		return;
	}

	@Override
	public List<MWVirtualMachine> listVMs() throws MWCloudException {
		/*
		 *  TODO: Implement method 
		 */
		return null;
	}

	@Override
	public Double getCPUUsage(MWVirtualMachine vm, int seconds) throws MWCloudException {
		/*
		 *  TODO: Implement method (optional for 5.0 ECTS)
		 */
		return null;
	}

	@Override
	public boolean isInstanceRunning(MWVirtualMachine vm) throws MWCloudException {
		return false;
	}

}
