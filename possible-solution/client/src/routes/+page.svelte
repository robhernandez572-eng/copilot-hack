<script lang="ts">
    import type { Airport } from "$lib";
    export let data;

    export let form;

    const airports: Airport[] = data.airports;

    // create an array of days for display
    // include the name starting with Monday and a value of 1
    // end with Sunday as 7
    const days = Array.from({length: 7}, (_, i) => {
        return {
            name: new Intl.DateTimeFormat('en-US', { weekday: 'long'}).format(new Date(0, 0, i + 1)),
            value: i + 1
        }
    });

    if(form && form.result) {
        console.log(form.result);
    }
</script>

<main>
    <h1>Flight Delay Predictor</h1>

    <form method="POST" action="?/getDelay">
        <label for="airport">Airport</label>
        <select id="airport" name="airport">
            {#each airports as airport (airport.id)}
                <option value={airport.id}>{airport.name}</option>
            {/each}
        </select>

        <label for="day">Day of Week</label>
        <select id="day" name="day">
            {#each days as day (day.value)}
                <option value={day.value}>{day.name}</option>
            {/each}
        </select>

        <button type="submit">Find delay</button>
    </form>

    {#if form && form.result}
        <div class="result">
            There is a <strong>{Math.round(form.result.delay * 100)}%</strong> chance of a delay.
            We are <strong>{Math.round(form.result.certainty * 100)}%</strong> sure.
        </div>
    {/if}
</main>

<style>
    :global(*, *::before, *::after) {
        box-sizing: border-box;
    }

    :global(body) {
        margin: 0;
        font-family: system-ui, -apple-system, sans-serif;
        background: #f5f7fa;
        color: #1a1a2e;
    }

    main {
        max-width: 480px;
        margin: 0 auto;
        padding: 2rem 1rem;
    }

    h1 {
        font-size: clamp(1.4rem, 5vw, 2rem);
        margin: 0 0 1.5rem;
        text-align: center;
    }

    form {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        background: #fff;
        border-radius: 12px;
        padding: 1.5rem;
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
    }

    label {
        font-size: 0.875rem;
        font-weight: 600;
        color: #555;
        margin-bottom: -0.25rem;
    }

    select {
        width: 100%;
        padding: 0.75rem 1rem;
        font-size: 1rem;
        border: 1.5px solid #d0d5dd;
        border-radius: 8px;
        background: #fff;
        appearance: none;
        -webkit-appearance: none;
        background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='8' viewBox='0 0 12 8'%3E%3Cpath d='M1 1l5 5 5-5' stroke='%23666' stroke-width='1.5' fill='none' stroke-linecap='round'/%3E%3C/svg%3E");
        background-repeat: no-repeat;
        background-position: right 1rem center;
        cursor: pointer;
        min-height: 48px;
    }

    select:focus {
        outline: none;
        border-color: #3b82f6;
        box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.2);
    }

    button {
        width: 100%;
        padding: 0.875rem;
        font-size: 1rem;
        font-weight: 600;
        color: #fff;
        background: #3b82f6;
        border: none;
        border-radius: 8px;
        cursor: pointer;
        min-height: 48px;
        margin-top: 0.5rem;
        transition: background 0.15s;
    }

    button:hover {
        background: #2563eb;
    }

    button:active {
        background: #1d4ed8;
    }

    .result {
        margin-top: 1.25rem;
        padding: 1rem 1.25rem;
        background: #eff6ff;
        border: 1.5px solid #bfdbfe;
        border-radius: 10px;
        font-size: 1rem;
        line-height: 1.5;
        text-align: center;
    }
</style>
