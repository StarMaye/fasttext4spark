package fasttext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import fasttext.Args.loss_name;
import fasttext.Args.model_name;


/**
 * @author admin
 */
public class Model implements Serializable {

    static final int SIGMOID_TABLE_SIZE = 512;
    static final int MAX_SIGMOID = 8;
    static final int LOG_TABLE_SIZE = 512;

    static final int NEGATIVE_TABLE_SIZE = 10000000;


    private Matrix inputM;
    private Matrix outputM;
    private Args args_;
    private Vector hidden_;
    private Vector output_;
    private Vector grad_;
    private int wordDim;
    @SuppressWarnings("unused")
    private int inputVocabSize;
    private int outputClassSize;
    private float loss_;
    private long nExamples;
    private float[] t_sigmoid;
    private float[] t_log;
    /*
    * used for negative sampling:
    *
    */
    private List<Integer> negatives;
    private int negpos;

    // used for hierarchical softmax:
    private List<List<Integer>> paths;
    private List<List<Boolean>> codes;
    private List<Node> tree;

    public Random rng;

    public Model(Matrix wi, Matrix wo, Args args, int seed) {
        hidden_ = new Vector(args.dim);
        output_ = new Vector(wo.m_);
        grad_ = new Vector(args.dim);
        rng = new Random((long) seed);

        inputM = wi;
        outputM = wo;
        args_ = args;
        inputVocabSize = wi.m_;
        outputClassSize = wo.m_;
        wordDim = args.dim;
        negpos = 0;
        loss_ = 0.0f;
        nExamples = 1L;
        initSigmoid();
        initLog();
    }

    public float binaryLogistic(int target, boolean label, float lr) {
        float score = sigmoid(outputM.dotRow(hidden_, target));
        float alpha = lr * ((label ? 1.0f : 0.0f) - score);
        grad_.addRow(outputM, target, alpha);
        outputM.addRow(hidden_, target, alpha);
        if (label) {
            return -log(score);
        } else {
            return -log(1.0f - score);
        }
    }

    public float negativeSampling(int target, float lr) {
        float loss = 0.0f;
        grad_.zero();
        for (int n = 0; n <= args_.neg; n++) {
            if (n == 0) {
                loss += binaryLogistic(target, true, lr);
            } else {
                loss += binaryLogistic(getNegative(target), false, lr);
            }
        }
        return loss;
    }

    public float hierarchicalSoftmax(int target, float lr) {
        float loss = 0.0f;
        grad_.zero();
        final List<Boolean> binaryCode = codes.get(target);
        final List<Integer> pathToRoot = paths.get(target);
        for (int i = 0; i < pathToRoot.size(); i++) {
            loss += binaryLogistic(pathToRoot.get(i), binaryCode.get(i), lr);
        }
        return loss;
    }

    public void computeOutputSoftmax(Vector hidden, Vector output) {
        output.mul(outputM, hidden);
        float max = output.get(0), z = 0.0f;
        for (int i = 1; i < outputClassSize; i++) {
            max = Math.max(output.get(i), max);
        }
        for (int i = 0; i < outputClassSize; i++) {
            output.set(i, (float) Math.exp(output.get(i) - max));
            z += output.get(i);
        }
        for (int i = 0; i < outputClassSize; i++) {
            output.set(i, output.get(i) / z);
        }
    }

    public void computeOutputSoftmax() {
        computeOutputSoftmax(hidden_, output_);
    }

    public float softmax(int target, float lr) {
        grad_.zero();
        computeOutputSoftmax();
        for (int i = 0; i < outputClassSize; i++) {
            float label = (i == target) ? 1.0f : 0.0f;
            float alpha = lr * (label - output_.get(i));
            grad_.addRow(outputM, i, alpha);
            outputM.addRow(hidden_, i, alpha);
        }
        return -log(output_.get(target));
    }

    public void computeHidden(final List<Integer> input, Vector hidden) {
        Utils.checkArgument(hidden.size() == wordDim);
        hidden.zero();
        for (Integer it : input) {
            hidden.addRow(inputM, it);
        }
        hidden.mul(1.0f / input.size());
    }

    private Comparator<Pair<Float, Integer>> comparePairs = new Comp();

    public void predict(final List<Integer> input, int k, List<Pair<Float, Integer>> heap, Vector hidden,
                        Vector output) {
        Utils.checkArgument(k > 0);
        if (heap instanceof ArrayList) {
            ((ArrayList<Pair<Float, Integer>>) heap).ensureCapacity(k + 1);
        }
        computeHidden(input, hidden);
        if (args_.loss == loss_name.hs) {
            dfs(k, 2 * outputClassSize - 2, 0.0f, heap, hidden);
        } else {
            findKBest(k, heap, hidden, output);
        }
        Collections.sort(heap, comparePairs);
    }

    public void predict(final List<Integer> input, int k, List<Pair<Float, Integer>> heap) {
        predict(input, k, heap, hidden_, output_);
    }

    public void findKBest(int k, List<Pair<Float, Integer>> heap, Vector hidden, Vector output) {
        computeOutputSoftmax(hidden, output);
        for (int i = 0; i < outputClassSize; i++) {
            if (heap.size() == k && log(output.get(i)) < heap.get(heap.size() - 1).getKey()) {
                continue;
            }
            heap.add(new Pair<Float, Integer>(log(output.get(i)), i));
            Collections.sort(heap, comparePairs);
            if (heap.size() > k) {
                Collections.sort(heap, comparePairs);
                heap.remove(heap.size() - 1); // pop last
            }
        }
    }

    public void dfs(int k, int node, float score, List<Pair<Float, Integer>> heap, Vector hidden) {
        if (heap.size() == k && score < heap.get(heap.size() - 1).getKey()) {
            return;
        }

        if (tree.get(node).left == -1 && tree.get(node).right == -1) {
            heap.add(new Pair<Float, Integer>(score, node));
            Collections.sort(heap, comparePairs);
            if (heap.size() > k) {
                Collections.sort(heap, comparePairs);
                heap.remove(heap.size() - 1); // pop last
            }
            return;
        }

        float f = sigmoid(outputM.dotRow(hidden, node - outputClassSize));
        dfs(k, tree.get(node).left, score + log(1.0f - f), heap, hidden);
        dfs(k, tree.get(node).right, score + log(f), heap, hidden);
    }

    public void update(final List<Integer> input, int target, float lr) {
        Utils.checkArgument(target >= 0);
        Utils.checkArgument(target < outputClassSize);
        if (input.size() == 0) {
            return;
        }
        computeHidden(input, hidden_);

        if (args_.loss == loss_name.ns) {
            loss_ += negativeSampling(target, lr);
        } else if (args_.loss == loss_name.hs) {
            loss_ += hierarchicalSoftmax(target, lr);
        } else {
            loss_ += softmax(target, lr);
        }
        nExamples += 1;

        if (args_.model == model_name.sup) {
            grad_.mul(1.0f / input.size());
        }
        for (Integer it : input) {
            inputM.addRow(grad_, it, 1.0f);
        }
    }

    public void setTargetCounts(final List<Long> counts) {
        Utils.checkArgument(counts.size() == outputClassSize);
        if (args_.loss == loss_name.ns) {
            initTableNegatives(counts);
        }
        if (args_.loss == loss_name.hs) {
            buildTree(counts);
        }
    }

    public void initTableNegatives(final List<Long> counts) {
        negatives = new ArrayList<Integer>(counts.size());
        float z = 0.0f;
        for (int i = 0; i < counts.size(); i++) {
            z += (float) Math.pow(counts.get(i), 0.5f);
        }
        for (int i = 0; i < counts.size(); i++) {
            float c = (float) Math.pow(counts.get(i), 0.5f);
            for (int j = 0; j < c * NEGATIVE_TABLE_SIZE / z; j++) {
                negatives.add(i);
            }
        }
        Utils.shuffle(negatives, rng);
    }

    public int getNegative(int target) {
        int negative;
        do {
            negative = negatives.get(negpos);
            negpos = (negpos + 1) % negatives.size();
        } while (target == negative);
        return negative;
    }

    public void buildTree(final List<Long> counts) {
        paths = new ArrayList<List<Integer>>(outputClassSize);
        codes = new ArrayList<List<Boolean>>(outputClassSize);
        tree = new ArrayList<Node>(2 * outputClassSize - 1);

        for (int i = 0; i < 2 * outputClassSize - 1; i++) {
            Node node = new Node();
            node.parent = -1;
            node.left = -1;
            node.right = -1;
            node.count = 1000000000000000L;
            node.binary = false;
            tree.add(i, node);
        }
        for (int i = 0; i < outputClassSize; i++) {
            tree.get(i).count = counts.get(i);
        }
        int leaf = outputClassSize - 1;
        int node = outputClassSize;
        for (int i = outputClassSize; i < 2 * outputClassSize - 1; i++) {
            int[] mini = new int[2];
            for (int j = 0; j < 2; j++) {
                if (leaf >= 0 && tree.get(leaf).count < tree.get(node).count) {
                    mini[j] = leaf--;
                } else {
                    mini[j] = node++;
                }
            }
            tree.get(i).left = mini[0];
            tree.get(i).right = mini[1];
            tree.get(i).count = tree.get(mini[0]).count + tree.get(mini[1]).count;
            tree.get(mini[0]).parent = i;
            tree.get(mini[1]).parent = i;
            tree.get(mini[1]).binary = true;
        }
        for (int i = 0; i < outputClassSize; i++) {
            List<Integer> path = new ArrayList<Integer>();
            List<Boolean> code = new ArrayList<Boolean>();
            int j = i;
            while (tree.get(j).parent != -1) {
                path.add(tree.get(j).parent - outputClassSize);
                code.add(tree.get(j).binary);
                j = tree.get(j).parent;
            }
            paths.add(path);
            codes.add(code);
        }
    }

    public float getLoss() {
        return loss_ / nExamples;
    }

    private void initSigmoid() {
        t_sigmoid = new float[SIGMOID_TABLE_SIZE + 1];
        for (int i = 0; i < SIGMOID_TABLE_SIZE + 1; i++) {
            float x = (float) (i * 2 * MAX_SIGMOID) / SIGMOID_TABLE_SIZE - MAX_SIGMOID;
            t_sigmoid[i] = (float) (1.0f / (1.0f + Math.exp(-x)));
        }
    }

    private void initLog() {
        t_log = new float[LOG_TABLE_SIZE + 1];
        for (int i = 0; i < LOG_TABLE_SIZE + 1; i++) {
            float x = (float) (((float) (i) + 1e-5f) / LOG_TABLE_SIZE);
            t_log[i] = (float) Math.log(x);
        }
    }

    public float log(float x) {
        if (x > 1.0f) {
            return 0.0f;
        }
        int i = (int) (x * LOG_TABLE_SIZE);
        return t_log[i];
    }

    public float sigmoid(float x) {
        if (x < -MAX_SIGMOID) {
            return 0.0f;
        } else if (x > MAX_SIGMOID) {
            return 1.0f;
        } else {
            int i = (int) ((x + MAX_SIGMOID) * SIGMOID_TABLE_SIZE / MAX_SIGMOID / 2);
            return t_sigmoid[i];
        }
    }
}
