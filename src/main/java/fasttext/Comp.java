package fasttext;

import java.io.Serializable;
import java.util.Comparator;

/**
 * Created by admin on 2018/4/28.
 */
public class Comp implements Serializable, Comparator<Pair<Float, Integer>> {
    @Override
    public int compare(Pair<Float, Integer> o1, Pair<Float, Integer> o2) {
        return o2.getKey().compareTo(o1.getKey());
    }
}



