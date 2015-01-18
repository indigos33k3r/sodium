package chapter3.section3;

import pump.*;
import sodium.*;
import java.util.Optional;

public class LifeCycle {
    public Stream<Fuel> sStart;
    public Cell<Optional<Fuel>> fillActive;
    public Stream<End> sEnd;

    public enum End { END }

    private static Stream<Fuel> whenLifted(Stream<UpDown> sNozzle,
                                          Fuel nozzleFuel) {
        return sNozzle.filter(u -> u == UpDown.UP)
                      .map(u -> nozzleFuel);
    }

    private static Stream<End> whenSetDown(Stream<UpDown> sNozzle,
                Fuel nozzleFuel,
                Cell<Optional<Fuel>> fillActive) {
        return Stream.<End>filterOptional(
            sNozzle.snapshot(fillActive,
                (u,f) -> u == UpDown.DOWN &&
                         f.equals(Optional.of(nozzleFuel))
                                       ? Optional.of(End.END)
                                       : Optional.empty()));
    }

    public LifeCycle(Stream<UpDown> sNozzle1,
                     Stream<UpDown> sNozzle2,
                     Stream<UpDown> sNozzle3) {
        Stream<Fuel> eLiftNozzle = whenLifted(sNozzle1, Fuel.ONE).merge(
                                  whenLifted(sNozzle2, Fuel.TWO).merge(
                                  whenLifted(sNozzle3, Fuel.THREE)));
        CellLoop<Optional<Fuel>> fillActive = new CellLoop<>();
        this.fillActive = fillActive;
        this.sStart = Stream.filterOptional(
            eLiftNozzle.snapshot(fillActive, (newFuel, fillActive_) ->
                fillActive_.isPresent() ? Optional.empty()
                                        : Optional.of(newFuel)));
        this.sEnd = whenSetDown(sNozzle1, Fuel.ONE, fillActive).merge(
                    whenSetDown(sNozzle2, Fuel.TWO, fillActive).merge(
                    whenSetDown(sNozzle3, Fuel.THREE, fillActive)));
        fillActive.loop(
            sStart.map(f -> Optional.of(f))
                  .merge(sEnd.map(e -> Optional.empty()))
                  .hold(Optional.empty())
        );
    }
}

