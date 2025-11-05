package mw.hybridcloud;

public class MWCloudException extends Exception {
	
	public MWCloudException(String message) {
		super(message);
	}

	public MWCloudException(String message, Throwable cause) {
		super(message, cause);
	}

	public MWCloudException(Throwable cause) {
		super(cause);
	}

}
