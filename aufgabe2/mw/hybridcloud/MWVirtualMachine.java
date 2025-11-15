package mw.hybridcloud;

public class MWVirtualMachine {

	public final String vmId;
	public final String vmName;
	public final String address;
	public String lastState;
	public MWVirtualMachineProvider provider;

	public MWVirtualMachine(String vmId, String vmName, String address, MWVirtualMachineProvider provider) {
		this.vmId = vmId;
		this.vmName = vmName;
		this.address = address;
		this.provider = provider;
		this.lastState = "unknown";
	}

	@Override
	public String toString() {
		return String.format("%s | %s | %s | %s | %s", vmId, vmName, address, lastState, provider);
	}

	public static enum MWVirtualMachineProvider {
		AWS, OPENSTACK;

		static public MWVirtualMachineProvider fromString(String string) {
			return switch (string.toLowerCase()) {
				case "os", "openstack" -> MWVirtualMachineProvider.OPENSTACK;
				case "aws", "amazon" -> MWVirtualMachineProvider.AWS;
				default -> null;
			};
		}
	}

}
