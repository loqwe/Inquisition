package moe.dazecake.inquisition.service.impl;

public class SanityOcrResult {
    private int currentSanity;
    private int maxSanity;
    private double confidence;
    private int votes;

    public SanityOcrResult() {
    }

    public SanityOcrResult(int currentSanity, int maxSanity, double confidence, int votes) {
        this.currentSanity = currentSanity;
        this.maxSanity = maxSanity;
        this.confidence = confidence;
        this.votes = votes;
    }

    public int getCurrentSanity() {
        return currentSanity;
    }

    public int getMaxSanity() {
        return maxSanity;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getVotes() {
        return votes;
    }
}
