package mw.namenode;

import java.util.ArrayList;
import java.util.List;

public record MWFileMetaData(String name, int size, List<MWFileBlock> blocks) {
    public MWFileMetaData {
        // JSON-B will call this one with all 3 params
        if (blocks == null) {
            blocks = new ArrayList<>();
        }
    }

    public void addBlock(MWFileBlock block) {
        blocks.add(block);
    }
}
