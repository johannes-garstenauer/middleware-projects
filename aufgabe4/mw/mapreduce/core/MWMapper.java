package mw.mapreduce.core;

public class MWMapper implements Runnable {
    public void run (){}
    public void setContext ( MWMapContext context ){}
    protected void map( String key , String value , MWContext <?> context ) throws Exception {}
}
