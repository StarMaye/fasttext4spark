package fasttext;

import java.io.Serializable;

/**
 * Created by admin on 2018/4/28.
 */
public class Node implements Serializable {
    int parent;
    int left;
    int right;
    long count;
    boolean binary;

}
