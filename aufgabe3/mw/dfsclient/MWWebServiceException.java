package mw.dfsclient;


public class MWWebServiceException extends Exception {
	
	public MWWebServiceException(String message) {
		super(message);
	}

	public MWWebServiceException(String message, Throwable cause) {
		super(message, cause);
	}

	public MWWebServiceException(Throwable cause) {
		super(cause);
	}

}
