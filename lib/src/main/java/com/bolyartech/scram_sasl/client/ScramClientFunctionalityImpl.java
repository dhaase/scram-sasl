package com.bolyartech.scram_sasl.client;

import com.bolyartech.scram_sasl.common.Base64;
import com.bolyartech.scram_sasl.common.ScramException;
import com.bolyartech.scram_sasl.common.ScramUtils;
import com.bolyartech.scram_sasl.common.StringPrep;

import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Provides building blocks for creating SCRAM authentication client
 */
@SuppressWarnings("unused")
public class ScramClientFunctionalityImpl implements ScramClientFunctionality {
    private static final Pattern SERVER_FIRST_MESSAGE = Pattern.compile("r=([^,]*),s=([^,]*),i=(.*)$");
    private static final Pattern SERVER_FINAL_MESSAGE = Pattern.compile("v=([^,]*)$");

    private static final String GS2_HEADER = "n,,";
    private static final Charset ASCII = Charset.forName("ASCII");

    private final String mDigestName;
    private final String mHmacName;
    private final String mClientNonce;
    private String mClientFirstMessageBare;

    private boolean mIsSuccessful = false;

    private State mState = State.INITIAL;


    /**
     * Create new ScramClientFunctionalityImpl
     * @param digestName Digest to be used
     * @param hmacName HMAC to be used
     */
    public ScramClientFunctionalityImpl(String digestName, String hmacName) {
        this(digestName, hmacName, UUID.randomUUID().toString());
    }


    /**
     * Create new ScramClientFunctionalityImpl
     * @param digestName Digest to be used
     * @param hmacName HMAC to be used
     * @param clientNonce Client nonce to be used
     */
    public ScramClientFunctionalityImpl(String digestName, String hmacName, String clientNonce) {
        if (ScramUtils.isNullOrEmpty(digestName)) {
            throw new NullPointerException("digestName cannot be null or empty");
        }
        if (ScramUtils.isNullOrEmpty(hmacName)) {
            throw new NullPointerException("hmacName cannot be null or empty");
        }
        if (ScramUtils.isNullOrEmpty(clientNonce)) {
            throw new NullPointerException("clientNonce cannot be null or empty");
        }

        mDigestName = digestName;
        mHmacName = hmacName;
        mClientNonce = clientNonce;
    }


    /**
     * Prepares first client message
     *
     * You may want to use {@link StringPrep#isContainingProhibitedCharacters(String)} in order to check if the
     * username contains only valid characters
     * @param username Username
     * @return prepared first message
     * @throws ScramException if <code>username</code> contains prohibited characters
     */
    @Override
    public String prepareFirstMessage(String username) throws ScramException {
        if (mState != State.INITIAL) {
            throw new IllegalStateException("You can call this method only once");
        }

        try {
            mClientFirstMessageBare = "n=" + StringPrep.prepAsQueryString(username) + ",r=" + mClientNonce;
            mState = State.FIRST_PREPARED;
            return GS2_HEADER + mClientFirstMessageBare;
        } catch (StringPrep.StringPrepError e) {
            mState = State.ENDED;
            throw new ScramException("Username contains prohibited character");
        }
    }


    @Override
    public String prepareFinalMessage(String password, String serverFirstMessage) throws ScramException {
        if (mState != State.FIRST_PREPARED) {
            throw new IllegalStateException("You can call this method once only after " +
                    "calling prepareFirstMessage()");
        }

        Matcher m = SERVER_FIRST_MESSAGE.matcher(serverFirstMessage);
        if (!m.matches()) {
            mState = State.ENDED;
            return null;
        }

        String nonce = m.group(1);

        if (!nonce.startsWith(mClientNonce)) {
            mState = State.ENDED;
            return null;
        }


        String salt = m.group(2);
        String iterationCountString = m.group(3);
        int iterations = Integer.parseInt(iterationCountString);
        if (iterations <= 0) {
            mState = State.ENDED;
            return null;
        }


        try {
            byte[] saltedPassword = ScramUtils.generateSaltedPassword(password,
                    Base64.decode(salt),
                    iterations,
                    mHmacName);


            String clientFinalMessageWithoutProof = "c=" + Base64.encodeBytes(GS2_HEADER.getBytes(ASCII))
                    + ",r=" + nonce;

            String authMessage = mClientFirstMessageBare + "," + serverFirstMessage + "," + clientFinalMessageWithoutProof;

            byte[] clientKey = ScramUtils.computeHmac(saltedPassword, mHmacName, "Client Key");
            byte[] storedKey = MessageDigest.getInstance(mDigestName).digest(clientKey);

            byte[] clientSignature = ScramUtils.computeHmac(storedKey, mHmacName, authMessage);

            byte[] clientProof = clientKey.clone();
            for (int i = 0; i < clientProof.length; i++) {
                clientProof[i] ^= clientSignature[i];
            }

            mState = State.FINAL_PREPARED;
            return clientFinalMessageWithoutProof + ",p=" + Base64.encodeBytes(clientProof);
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            mState = State.ENDED;
            throw new ScramException(e);
        }
    }


    @Override
    public boolean checkServerFinalMessage(String serverFinalMessage) {
        if (mState != State.FINAL_PREPARED) {
            throw new IllegalStateException("You can call this method only once after " +
                    "calling prepareFinalMessage()");
        }

        Matcher m = SERVER_FINAL_MESSAGE.matcher(serverFinalMessage);
        if (!m.matches()) {
            mState = State.ENDED;
            return false;
        }

        byte[] serverSignature = Base64.decode(m.group(1));

        mState = State.ENDED;
        mIsSuccessful = Arrays.equals(serverSignature, serverSignature);

        return mIsSuccessful;
    }


    @Override
    public boolean isSuccessful() {
        if (mState == State.ENDED) {
            return mIsSuccessful;
        } else {
            throw new IllegalStateException("You cannot call this method before authentication is ended. " +
                    "Use isEnded() to check that");
        }
    }


    @Override
    public boolean isEnded() {
        return mState == State.ENDED;
    }


    @Override
    public State getState() {
        return mState;
    }
}