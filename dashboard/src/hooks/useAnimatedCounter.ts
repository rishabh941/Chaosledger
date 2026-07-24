import { useState, useEffect, useRef } from "react";

export function useAnimatedCounter(target: number, duration = 1200): number {
    const [value, setValue] = useState(0);
    const frameRef = useRef<number>(0);
    const startRef = useRef(0);

    useEffect(() => {
        startRef.current = value;
    });

    useEffect(() => {
        const start = startRef.current;
        const diff = target - start;
        if (Math.abs(diff) < 1) {
            setValue(target);
            return;
        }

        const startTime = performance.now();

        const animate = (now: number) => {
            const elapsed = now - startTime;
            const progress = Math.min(elapsed / duration, 1);
            const eased = 1 - Math.pow(1 - progress, 3);
            setValue(Math.round(start + diff * eased));
            if (progress < 1) {
                frameRef.current = requestAnimationFrame(animate);
            }
        };

        frameRef.current = requestAnimationFrame(animate);
        return () => cancelAnimationFrame(frameRef.current);
    }, [target, duration]);

    return value;
}
