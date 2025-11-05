package mw.hybridcloud;

public class MWVirtualMachine {

	public final String vmId;
	public final String vmName;
	public final String address;
	public String lastState;

	public MWVirtualMachine(String vmId, String vmName, String address) {
		this.vmId = vmId;
		this.vmName = vmName;
		this.address = address;
		this.lastState = "unknown";
	}

	@Override
	public String toString() {
		return String.format("%s | %s | %s | %s", vmId, vmName, address, lastState);
	}

}
