package lld.notificationservice.channel;

import java.util.Objects;

/** Immutable outcome of a single provider dispatch call. */
public final class DispatchResult {

    private final FailureType failureType;
    private final String providerMessageId; // non-null on SUCCESS
    private final String errorCode;         // non-null on failure
    private final String errorDetail;

    private DispatchResult(FailureType failureType, String providerMessageId,
                           String errorCode, String errorDetail) {
        this.failureType       = Objects.requireNonNull(failureType);
        this.providerMessageId = providerMessageId;
        this.errorCode         = errorCode;
        this.errorDetail       = errorDetail;
    }

    public static DispatchResult success(String providerMessageId) {
        return new DispatchResult(FailureType.SUCCESS, providerMessageId, null, null);
    }

    public static DispatchResult hardFail(String errorCode, String detail) {
        return new DispatchResult(FailureType.HARD_FAIL, null, errorCode, detail);
    }

    public static DispatchResult softFail(String errorCode, String detail) {
        return new DispatchResult(FailureType.SOFT_FAIL, null, errorCode, detail);
    }

    public FailureType getFailureType()      { return failureType; }
    public String getProviderMessageId()     { return providerMessageId; }
    public String getErrorCode()             { return errorCode; }
    public String getErrorDetail()           { return errorDetail; }
    public boolean isSuccess()               { return failureType == FailureType.SUCCESS; }

    @Override
    public String toString() {
        if (failureType == FailureType.SUCCESS) {
            return "DispatchResult{SUCCESS, msgId=" + providerMessageId + "}";
        }
        return "DispatchResult{" + failureType + ", code=" + errorCode + ", detail=" + errorDetail + "}";
    }
}
