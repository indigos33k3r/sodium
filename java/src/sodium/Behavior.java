package sodium;

public class Behavior<A> {
	protected Event<A> event;
	A value;
	A valueUpdate;
	private Listener cleanup;

    Behavior(Event<A> event, A initValue)
    {
    	this.event = event;
    	this.value = initValue;
    	Transaction.run((Transaction trans1) -> {
    		this.cleanup = event.listen(Node.NULL, trans1, (Transaction trans2, A a) -> {
	    		if (this.valueUpdate == null) {
	    			trans2.last(() -> {
	    				this.value = this.valueUpdate;
	    				this.valueUpdate = null;
	    			});
	    		}
	    		this.valueUpdate = a;
	    	}, false);
    	});
    }

    /**
     * @return The value including any updates that have happened in this transaction.
     */
    final A newValue()
    {
    	return valueUpdate == null ? value :  valueUpdate;
    }

    public final Event<A> changes()
    {
    	return event;
    }

    public final Event<A> values()
    {
        return Transaction.evaluate((Transaction trans) -> values(trans));
    }

    final Event<A> values(Transaction trans1)
    {
    	EventSink<A> out = new EventSink<A>() {
    		@Override
            protected Object[] sampleNow()
            {
                return new Object[] { value };
            }
    	};
        Listener l = event.listen(out.node, trans1, (Transaction trans2, A a) -> { out.send(trans2, a); }, false);
        return out.addCleanup(l)
            .lastFiringOnly(trans1);  // Needed in case of an initial value and an update
    	                              // in the same transaction.
    }

	public final <B> Behavior<B> map(Lambda1<A,B> f)
	{
		return changes().map(f).hold(f.evaluate(value));
	}
	
	public final <B,C> Behavior<C> lift(Lambda2<A,B,C> f, Behavior<B> b)
	{
		Lambda1<A, Lambda1<B,C>> ffa = (A aa) -> (B bb) -> f.evaluate(aa,bb);
		Behavior<Lambda1<B,C>> bf = map(ffa);
		return apply(bf, b);
	}

	public static final <A,B,C> Behavior<C> lift(Lambda2<A,B,C> f, Behavior<A> a, Behavior<B> b)
	{
		return a.lift(f, b);
	}

	public static <A,B> Behavior<B> apply(final Behavior<Lambda1<A,B>> bf, final Behavior<A> ba)
	{
		final EventSink<B> out = new EventSink();

        Handler<Transaction> h = new Handler<Transaction>() {
            boolean fired = false;			
            @Override
            public void run(Transaction trans1) {
                if (fired) 
                    return;
                
                fired = true;
                trans1.prioritized(out.node, (Transaction trans2) -> {
                   out.send(trans2, bf.newValue().evaluate(ba.newValue()));
                   fired = false;
                });
            }
        };
    
        Listener l1 = bf.changes().listen_(out.node, (Transaction trans1, Lambda1<A,B> f) -> {
            h.run(trans1);
        });
        Listener l2 = ba.changes().listen_(out.node, (Transaction trans1, A a) -> {
            h.run(trans1);
        });
        return out.addCleanup(l1).addCleanup(l2).hold(bf.value.evaluate(ba.value));
	}

	public static <A> Behavior<A> switchB(final Behavior<Behavior<A>> bba)
	{
	    A za = bba.value.value;
	    final EventSink<A> out = new EventSink<A>();
        TransactionHandler<Behavior<A>> h = new TransactionHandler<Behavior<A>>() {
            private Listener currentListener;
            @Override
            public void run(Transaction trans2, Behavior<A> ba) {
                // Note: If any switch takes place during a transaction, then the
                // values().listen will always cause a sample to be fetched from the
                // one we just switched to. The caller will be fetching our output
                // using values().listen, and values() throws away all firings except
                // for the last one. Therefore, anything from the old input behaviour
                // that might have happened during this transaction will be suppressed.
                if (currentListener != null)
                    currentListener.unlisten();
                currentListener = ba.values(trans2).listen(out.node, trans2, (Transaction trans3, A a) -> {
                    out.send(trans3, a);
                }, false);
            }

            @Override
            protected void finalize() throws Throwable {
                if (currentListener != null)
                    currentListener.unlisten();
            }
        };
        Listener l1 = bba.values().listen_(out.node, h);
        return out.addCleanup(l1).hold(za);
	}
	
	public static <A> Event<A> switchE(final Behavior<Event<A>> bea)
	{
        return Transaction.evaluate((final Transaction trans) -> switchE(trans, bea));
    }

	private static <A> Event<A> switchE(final Transaction trans1, final Behavior<Event<A>> bea)
	{
        final EventSink<A> out = new EventSink<A>();
        final TransactionHandler<A> h2 = (Transaction trans2, A a) -> {
            out.send(trans2, a);
        };
        TransactionHandler<Event<A>> h1 = new TransactionHandler<Event<A>>() {
            private Listener currentListener = bea.value.listen(out.node, trans1, h2, false);

            @Override
            public void run(Transaction trans2, Event<A> ea) {
                trans2.last(() -> {
                    if (currentListener != null)
                        currentListener.unlisten();
                    currentListener = ea.listen(out.node, trans2, h2, true);
                });
            }

            @Override
            protected void finalize() throws Throwable {
                if (currentListener != null)
                    currentListener.unlisten();
            }
        };
        Listener l1 = bea.changes().listen(out.node, trans1, h1, false);
        return out.addCleanup(l1);
	}

    public static <A,B> B loop(final Lambda1<Behavior<A>,Tuple2<B,Behavior<A>>> f)
    {
        return Event.loop(
            // Lambda syntax doesn't work here - compiler bug?
            new Lambda1<Event<A>,Tuple2<B,Event<A>>>() {
                public Tuple2<B,Event<A>> evaluate(Event<A> ea) {
                    Tuple2<B,Behavior<A>> b_ba = f.evaluate(ea.hold(null));
                    return new Tuple2(b_ba.a, b_ba.b.values());
                }
            }
        );
    }

    public final <B,S> Behavior<B> collect(final S initState, final Lambda2<A, S, Tuple2<B, S>> f)
    {
        final Event<A> ea = changes().coalesce((A fst, A snd) -> snd);
        final A za = value;
        final Tuple2<B, S> zbs = f.evaluate(za, initState);
        return Event.loop(
            new Lambda1<Event<Tuple2<B,S>>, Tuple2<Behavior<B>,Event<Tuple2<B,S>>>>() {
                public Tuple2<Behavior<B>,Event<Tuple2<B,S>>> evaluate(Event<Tuple2<B,S>> ebs) {
                    Behavior<Tuple2<B,S>> bbs = ebs.hold(zbs);
                    Behavior<S> bs = bbs.map((x->x.b));
                    Event<Tuple2<B,S>> ebs_out = ea.snapshot(bs, f);
                    return new Tuple2<Behavior<B>,Event<Tuple2<B,S>>>(bbs.map(x->x.a), ebs_out);
                }
            }
        );
    }

    public final <S> Behavior<S> accum(final S initState, final Lambda2<A, S, S> f)
    {
        final Event<A> ea = changes().coalesce((A fst, A snd) -> snd);
        final A za = value;
        final S zs = f.evaluate(za, initState);
        return Event.loop(
            new Lambda1<Event<S>, Tuple2<Behavior<S>,Event<S>>>() {
                public Tuple2<Behavior<S>,Event<S>> evaluate(Event<S> es) {
                    Behavior<S> bs = es.hold(zs);
                    Event<S> es_out = ea.snapshot(bs, f);
                    return new Tuple2<Behavior<S>,Event<S>>(bs, es_out);
                }
            }
        );
    }

	@Override
	protected void finalize() throws Throwable {
		cleanup.unlisten();
	}
}
