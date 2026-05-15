package ym.ymshop.testsupport;

class PackagePrivateTarget {

    private boolean cancelled;

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
