package mw.namenode;

import java.util.ArrayList;
import java.util.List;

public record MWFileMetaData(String name, int size, List<MWFileBlock> blocks) {
    public MWFileMetaData(String name, int size) {
        this(name, size, new ArrayList<>());
    }

    public void addBlock(MWFileBlock block) {
        blocks.add(block);
    }
}
