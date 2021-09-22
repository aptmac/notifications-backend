import { useMutation } from 'react-fetching-library';

import { Operations } from '../generated/OpenapiInternal';

export type CreateEventType = {
    id: string;
    displayName: string;
    description: string;
    applicationId: string;
    name: string;

}

const actionCreator = (params: CreateEventType) => Operations.InternalServiceCreateEventType.actionCreator({
    body: {
        id: params.id,
        application_id: params.applicationId,
        description: params.description,
        display_name: params.displayName,
        name: params.name

    }
});

export const useCreateEventType = () => {
    return useMutation(actionCreator);
};