import { Moment } from "moment";
import HttpService from "../../http/HttpService";
import { ThunkAction } from "../reduxTypes";
import { ProcessCounts } from "../../reducers/graph";
import { Process } from "../../types";

export interface DisplayProcessCountsAction {
    processCounts: ProcessCounts;
    type: "DISPLAY_PROCESS_COUNTS";
}

export function displayProcessCounts(processCounts: ProcessCounts): DisplayProcessCountsAction {
    return {
        type: "DISPLAY_PROCESS_COUNTS",
        processCounts,
    };
}

const checkPossibleCountsToCalculate = (processCounts: ProcessCounts, processToDisplay: Process) => {
    const processCountsName = Object.keys(processCounts).sort((a, b) => a.localeCompare(b));
    const uncountableNodes = processToDisplay.nodes
        .sort((a, b) => a.id.localeCompare(b.id))
        .filter((node, index) => node.id !== processCountsName[index]);
    const newProcessCounts = { ...processCounts };
    if (uncountableNodes.length !== 0 && processCountsName.length !== 0) {
        for (let i = 0; i < uncountableNodes.length; i++) {
            newProcessCounts[uncountableNodes[i].id] = {
                all: undefined,
                errors: 0,
                fragmentCounts: {},
            };
        }
    }
    return newProcessCounts;
};

export function fetchAndDisplayProcessCounts(
    processName: string,
    from: Moment,
    to: Moment,
    processToDisplay: Process,
): ThunkAction<Promise<DisplayProcessCountsAction>> {
    return (dispatch) => {
        return HttpService.fetchProcessCounts(processName, from, to).then((response) => {
            const newProcessCounts = checkPossibleCountsToCalculate(response.data, processToDisplay);
            return dispatch(displayProcessCounts(newProcessCounts));
        });
    };
}
