package mw.namenode;

import java.util.ArrayList;
import java.util.List;

public record MWFileMetaData(String name, int size, List<MWNodeMetaData> blocks) {
    public MWFileMetaData(String name, int size) {
        this(name, size, new ArrayList<>());
    }

    public void addBlock(MWNodeMetaData block) {
        blocks.add(block);
    }
}
