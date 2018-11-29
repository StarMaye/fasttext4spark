package fasttext;

import java.io.Serializable;
import java.util.List;

/**
 * Created by admin on 2018/4/28.
 */
public class entry implements Serializable {
    String word;
    long count;
    Dictionary.entry_type type;
    List<Integer> subwords;

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("entry [word=");
        builder.append(word);
        builder.append(", count=");
        builder.append(count);
        builder.append(", type=");
        builder.append(type);
        builder.append(", subwords=");
        builder.append(subwords);
        builder.append("]");
        return builder.toString();
    }
}