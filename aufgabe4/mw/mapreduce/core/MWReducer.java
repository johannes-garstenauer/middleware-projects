package mw.mapreduce.core;

public class MWReducer implements Runnable {
    public void run (){}
    public void setContext ( MWReduceContext context ){}
    protected void reduce ( String key , Iterable <String > values ,
                            MWContext <?> context ) throws Exception {}
}
