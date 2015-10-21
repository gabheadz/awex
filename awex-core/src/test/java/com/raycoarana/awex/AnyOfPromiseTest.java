package com.raycoarana.awex;

import com.raycoarana.awex.exceptions.AllFailException;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AnyOfPromiseTest extends BasePromiseTest {

    private static final Integer SOME_RESULT_VALUE = 42;

    private AwexPromise<Integer> mFirstPromise;
    private AwexPromise<Integer> mSecondPromise;
    private AnyOfPromise<Integer> mAnyOfPromise;

    @Test
    public void shouldReturnValueOfFirstPromise() throws Exception {
        setUpAwex();

        setUpPromises();

        mFirstPromise.resolve(SOME_RESULT_VALUE);

        assertEquals(mAnyOfPromise.getResult(), SOME_RESULT_VALUE);
    }

    @Test
    public void shouldReturnValueOfSecondPromise() throws Exception {
        setUpAwex();

        setUpPromises();

        mSecondPromise.resolve(SOME_RESULT_VALUE);

        assertEquals(mAnyOfPromise.getResult(), SOME_RESULT_VALUE);
    }

    @Test(expected = AllFailException.class)
    public void shouldFailIfAllPromisesFails() throws Exception {
        setUpAwex();

        setUpPromises();

        mFirstPromise.reject(new Exception("First"));
        mSecondPromise.reject(new Exception("Second"));

        mAnyOfPromise.getResult();
    }

    @Test
    public void shouldCreateAllExceptionWithFirstAndSecondPromiseExceptions() throws Exception {
        setUpAwex();

        setUpPromises();

        mFirstPromise.reject(new Exception("First"));
        mSecondPromise.reject(new Exception("Second"));

        try {
            mAnyOfPromise.getResult();
            throw new IllegalStateException("Invalid promise state");
        } catch (AllFailException ex) {
            assertEquals(2, ex.getCount());
            assertEquals("First", ex.getException(0).getMessage());
            assertEquals("Second", ex.getException(1).getMessage());
        }
    }

    @Test
    public void shouldBeCancelledOnceAnyPromiseIsCancelled() throws Exception {
        setUpAwex();

        setUpPromises();

        mSecondPromise.cancelWork();

        assertTrue(mAnyOfPromise.isCancelled());
    }

    private void setUpPromises() {
        mFirstPromise = new AwexPromise<>(mAwex, mWork);
        mSecondPromise = new AwexPromise<>(mAwex, mWork);
        mAnyOfPromise = new AnyOfPromise<>(mAwex,
                Arrays.<Promise<Integer>>asList(mFirstPromise, mSecondPromise));
    }

}