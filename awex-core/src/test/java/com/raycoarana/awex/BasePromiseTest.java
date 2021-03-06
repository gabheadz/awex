package com.raycoarana.awex;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

public class BasePromiseTest {

    private static final long SOME_WORK_ID = 42;

    @Mock
    protected Awex mAwex;
    @Mock
    protected Task mTask;
    @Mock
    protected ThreadHelper mThreadHelper;
    @Mock
    protected Logger mLogger;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    protected void setUpAwex() {
        givenAnAwex();
        givenAWork();
        givenAUIThread();
    }

    private void givenAnAwex() {
        when(mAwex.provideLogger()).thenReturn(mLogger);
        when(mAwex.provideUIThread()).thenReturn(mThreadHelper);
        when(mAwex.getNumberOfThreads()).thenReturn(4);

        when(mAwex.newAwexPromise()).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                return new AwexPromise<>(mAwex, mTask);
            }
        });
        doAnswer(new Answer<Promise>() {
            @Override
            public Promise answer(InvocationOnMock invocation) throws Throwable {
                Task task = ((Task) invocation.getArguments()[0]);
                task.initialize(mAwex);
                task.markQueue(null);
                task.execute();
                return task.getPromise();
            }
        }).when(mAwex).submit(isA(Task.class));

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArguments()[0]).run();
                return null;
            }
        }).when(mAwex).submit(isA(Runnable.class));
    }

    private void givenAWork() {
        when(mTask.getId()).thenReturn(SOME_WORK_ID);
    }

    protected void givenThatUIThreadIsCurrentThread() {
        when(mThreadHelper.isCurrentThread()).thenReturn(true);
    }

    private void givenAUIThread() {
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                ((Runnable) invocation.getArguments()[0]).run();
                return null;
            }
        }).when(mThreadHelper).post(isA(Runnable.class));
    }
}
