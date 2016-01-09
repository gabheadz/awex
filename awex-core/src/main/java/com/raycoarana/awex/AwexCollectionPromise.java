package com.raycoarana.awex;

import com.raycoarana.awex.callbacks.CancelCallback;
import com.raycoarana.awex.callbacks.DoneCallback;
import com.raycoarana.awex.callbacks.FailCallback;
import com.raycoarana.awex.transform.Filter;
import com.raycoarana.awex.transform.Func;
import com.raycoarana.awex.transform.Mapper;

import java.util.Collection;
import java.util.Collections;

class AwexCollectionPromise<T> extends AwexPromise<Collection<T>> implements CollectionPromise<T> {

    public AwexCollectionPromise(Awex awex) {
        super(awex);
    }

    public <U> AwexCollectionPromise(Awex mAwex, Promise<U> promise) {
        super(mAwex);

        promise.done(new DoneCallback<U>() {
            @SuppressWarnings("unchecked")
            @Override
            public void onDone(U result) {
                Collection<T> collectionResult;
                if (result instanceof Collection) {
                    collectionResult = (Collection<T>) result;
                } else {
                    collectionResult = (Collection<T>) Collections.singleton(result);
                }
                AwexCollectionPromise.this.resolve(collectionResult);
            }
        }).fail(new FailCallback() {
            @Override
            public void onFail(Exception exception) {
                AwexCollectionPromise.this.reject(exception);
            }
        }).cancel(new CancelCallback() {
            @Override
            public void onCancel() {
                AwexCollectionPromise.this.cancelTask();
            }
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    public <U> CollectionPromise<U> stream() {
        return (CollectionPromise<U>) this;
    }

    @Override
    public CollectionPromise<T> filter(Filter<T> filter) {
        return new SingleThreadFilterPromise<>(mAwex, this, filter);
    }

    @Override
    public CollectionPromise<T> filterParallel(Filter<T> filter) {
        if (mAwex.getNumberOfThreads() > 1) {
            return new MultiThreadFilterPromise<>(mAwex, this, filter);
        } else {
            return filter(filter);
        }
    }

    @Override
    public <U> CollectionPromise<U> map(Mapper<T, U> mapper) {
        return new SingleThreadMapperPromise<>(mAwex, this, mapper);
    }

    @Override
    public <U> CollectionPromise<U> mapParallel(Mapper<T, U> mapper) {
        if (mAwex.getNumberOfThreads() > 1) {
            return new MultiThreadMapperPromise<>(mAwex, this, mapper);
        } else {
            return map(mapper);
        }
    }

    @Override
    public CollectionPromise<T> forEach(Func<T> func) {
        return new SingleThreadForEachPromise<>(mAwex, this, func);
    }

    @Override
    public CollectionPromise<T> forEachParallel(Func<T> func) {
        if (mAwex.getNumberOfThreads() > 1) {
            return new MultiThreadForEachPromise<>(mAwex, this, func);
        } else {
            return forEach(func);
        }
    }

    @Override
    public Promise<T> singleOrFirst() {
        return new MapperTransformerPromise<>(mAwex, this, new Mapper<Collection<T>, T>() {
            @Override
            public T map(Collection<T> value) {
                return value.size() > 0 ? value.iterator().next() : null;
            }
        });
    }

}
